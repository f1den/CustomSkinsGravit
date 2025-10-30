package xyz.nightsync.hdskinssupportgravit.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

import xyz.nightsync.hdskinssupportgravit.HDSkinsSupportGravitMod;
import xyz.nightsync.hdskinssupportgravit.utils.GifDecoder;

public final class CapeManager {

    private static volatile double SPEED_MULTIPLIER = 3.0;
    public static void setSpeed(double mul) { SPEED_MULTIPLIER = Math.max(0.1, mul); }

    // анти-краш лимиты
    private static final int MAX_W = 1024;
    private static final int MAX_H = 1024;
    private static final int MAX_FRAMES = 120;                 // максимум кадров
    private static final long MAX_TOTAL_PIXELS = 32_000_000L;   // суммарно по всем кадрам (32M)
    private static final int MAX_BYTES = 10 * 1024 * 1024;     // максимум байт файла (10 МБ)
    private static final int MIN_DELAY_MS = 15;                // минимальная задержка между кадрами
    private static final int MAX_DELAY_MS = 5000;              // максимальная задержка между кадрами

    private static final ExecutorService IO_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Cape-Loader");
        t.setDaemon(true);
        return t;
    });

    private static final Map<UUID, CapeData> CAPES = new ConcurrentHashMap<>();
    private static final String API_FMT = "https://nightsync.xyz/api/minecraft/getcape/%s";

    public static ResourceLocation getCape(UUID uuid) {
        CapeData data = CAPES.computeIfAbsent(uuid, CapeManager::startLoading);
        return data != null ? data.currentTexture() : null;
    }

    private static CapeData startLoading(UUID uuid) {
        CapeData data = new CapeData(uuid);
        IO_POOL.submit(() -> {
            try {
                byte[] bytes = httpGet(String.format(API_FMT, uuid));
                if (bytes == null || bytes.length < 6) {
                    data.setStatic(null);
                    return;
                }
                // GIF?
                if (isGif(bytes)) {
                    loadGifCape(data, bytes);
                } else {
                    loadPngCape(data, bytes);
                }
            } catch (Throwable t) {
                t.printStackTrace();
                data.setStatic(null);
            }
        });
        return data;
    }

    private static boolean isGif(byte[] bytes) {
        if (bytes.length < 6) return false;
        String head = new String(bytes, 0, 6);
        return "GIF87a".equals(head) || "GIF89a".equals(head);
    }

    private static void loadPngCape(CapeData data, byte[] bytes) throws IOException {
        try (NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes))) {
            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H) { data.setStatic(null); return; }

            DynamicTexture dyn = new DynamicTexture(img);
            ResourceLocation id = rl("cape/" + data.uuid);
            Minecraft.getInstance().getTextureManager().register(id, dyn);
            data.setStatic(id);
        } catch (OutOfMemoryError oom) {
            data.setStatic(null);
        }
    }

    private static void loadGifCape(CapeData data, byte[] bytes) throws IOException {
        // 1) Декод и подготовка на любом (IO) потоке: БЕЗ GL
        GifDecoder.GifImage gif = GifDecoder.read(new ByteArrayInputStream(bytes));
        // если добавлял режим без кэша кадров — задействуй
        try {
            gif.getClass().getMethod("setCacheFrames", boolean.class).invoke(gif, false);
        } catch (Throwable ignored) {}

        int w = gif.getWidth();
        int h = gif.getHeight();
        if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H) { data.setStatic(null); return; }

        int framesAll = gif.getFrameCount();
        if (framesAll <= 0) { data.setStatic(null); return; }

        // Бюджет по пикселям (под стриминг можно держать больше)
        long totalAll = 1L * w * h * framesAll;
        int frames = framesAll;
        if (totalAll > MAX_TOTAL_PIXELS) {
            frames = (int) Math.max(1, MAX_TOTAL_PIXELS / Math.max(1L, w * (long) h));
        }
        frames = Math.min(frames, MAX_FRAMES);

        // Подготовим массив задержек (ускорение SPEED_MULTIPLIER, клампы)
        int[] delaysMs = new int[frames];
        for (int i = 0; i < frames; i++) {
            int delayCs = Math.max(1, gif.getDelay(i));     // сотые секунды
            int rawMs   = delayCs * 10;
            int clamped = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, rawMs));
            delaysMs[i] = (int) Math.max(MIN_DELAY_MS, Math.round(clamped / SPEED_MULTIPLIER));
        }

        // 2) ВСЁ, ЧТО ТРОГАЕТ GL/RenderSystem — ТОЛЬКО НА render-потоке:
        RenderSystem.recordRenderCall(() -> {
            try {
                // одна текстура под всю анимацию (стриминг кадров в неё)
                DynamicTexture dyn = new DynamicTexture(w, h, true);
                ResourceLocation id = rl("cape/" + data.uuid + "/anim");
                Minecraft.getInstance().getTextureManager().register(id, dyn);

                // ВНИМАНИЕ: внутри setAnimatedStreaming НИКАКИХ GL-вызовов!
                // только сохранить gif, dyn, id, delays и выставить индексы/таймер.
                data.setAnimatedStreaming(gif, dyn, id, delaysMs);
            } catch (Throwable t) {
                t.printStackTrace();
                data.setStatic(null);
            }
        });
    }

    private static byte[] httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(7000);
        c.setReadTimeout(10000);
        c.addRequestProperty("User-Agent", HDSkinsSupportGravitMod.MODID + "/1.0");

        int code = c.getResponseCode();
        if (code != 200) return null;

        int len = c.getContentLength();
        if (len > 0 && len > MAX_BYTES) return null;

        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            int read;
            int total = 0;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(Math.max(len, 0), MAX_BYTES));
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > MAX_BYTES) return null; // hard cap
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(HDSkinsSupportGravitMod.MODID, path.replaceAll("[^a-zA-Z0-9/_.-]", "_"));
    }

    public static void clientTick() {
        long now = System.currentTimeMillis();
        CAPES.values().forEach(cd -> cd.tick(now));
    }

    // ==== модель данных ====

    private static final class CapeData {
        final UUID uuid;

        // статический плащ
        private ResourceLocation staticTex;

        // стриминг
        private GifDecoder.GifImage gif;
        private DynamicTexture dyn;
        private ResourceLocation dynId;
        private int[] delays;
        private int idx;
        private long nextAt;

        CapeData(UUID uuid) { this.uuid = uuid; }

        void setStatic(ResourceLocation id) {
            this.staticTex = id;
            this.gif = null; this.dyn = null; this.dynId = null; this.delays = null;
        }

        void setAnimatedStreaming(GifDecoder.GifImage gif, DynamicTexture dyn, ResourceLocation id, int[] delays) {
            this.gif = gif;
            this.dyn = dyn;
            this.dynId = id;
            this.delays = delays;
            this.idx = -1; // чтобы сразу отрисовать 0-й кадр
            this.nextAt = 0L;
        }

        ResourceLocation currentTexture() {
            if (dynId != null) return dynId;
            return staticTex;
        }

        void tick(long now) {
            if (dyn == null || gif == null || delays == null) return;

            if (idx == -1 || now >= nextAt) {
                idx = (idx + 1) % delays.length;

                BufferedImage bi = gif.renderFrameNoCache(idx);
                if (bi != null) {
                    NativeImage ni = dyn.getPixels(); // CPU-буфер
                    int w = ni.getWidth(), h = ni.getHeight();
                    int[] argb = new int[w * h];
                    bi.getRGB(0, 0, w, h, argb, 0, w);

                    // заполняем пиксели (CPU) — можно на любом потоке
                    for (int y = 0; y < h; y++) {
                        int row = y * w;
                        for (int x = 0; x < w; x++) {
                            int p = argb[row + x];
                            int a = (p >>> 24) & 0xFF;
                            int r = (p >>> 16) & 0xFF;
                            int g = (p >>> 8) & 0xFF;
                            int b = (p) & 0xFF;
                            int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                            ni.setPixelRGBA(x, y, abgr);
                        }
                    }

                    // ПЕРЕНОС НА GPU — строго на render-потоке
                    RenderSystem.recordRenderCall(dyn::upload);

                    bi.flush();
                }

                nextAt = now + Math.max(MIN_DELAY_MS, delays[idx]);
            }
        }

        void releaseAll() {
            var tm = Minecraft.getInstance().getTextureManager();
            if (dynId != null) tm.release(dynId);
            if (staticTex != null) tm.release(staticTex);
            dynId = null; dyn = null; staticTex = null; gif = null; delays = null;
        }
    }

    private record CapeFrame(ResourceLocation id, int delayMs) {}
}
