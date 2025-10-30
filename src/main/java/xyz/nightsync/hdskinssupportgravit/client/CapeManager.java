package xyz.nightsync.hdskinssupportgravit.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

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
    private static final long MAX_TOTAL_PIXELS = 8_388_608L;   // суммарно по всем кадрам (8M)
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
        try {
            GifDecoder.GifImage gif = GifDecoder.read(new ByteArrayInputStream(bytes));

            int w = gif.getWidth();
            int h = gif.getHeight();
            if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H) { data.setStatic(null); return; }

            int framesAll = gif.getFrameCount();
            if (framesAll <= 0) { data.setStatic(null); return; }

            int frames = Math.min(framesAll, MAX_FRAMES);
            long totalPx = (long) w * h * frames;
            if (totalPx > MAX_TOTAL_PIXELS) { data.setStatic(null); return; }

            List<CapeFrame> list = new ArrayList<>(frames);

            for (int i = 0; i < frames; i++) {
                java.awt.image.BufferedImage bi = gif.getFrame(i);

                NativeImage ni = new NativeImage(NativeImage.Format.RGBA, w, h, true);
                int[] argb = new int[w * h];
                bi.getRGB(0, 0, w, h, argb, 0, w);

                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int p = argb[y * w + x];
                        int a = (p >>> 24) & 0xFF;
                        int r = (p >>> 16) & 0xFF;
                        int g = (p >>> 8) & 0xFF;
                        int b = (p) & 0xFF;
                        int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                        ni.setPixelRGBA(x, y, abgr);
                    }
                }

                DynamicTexture dyn = new DynamicTexture(ni);
                ResourceLocation id = rl("cape/" + data.uuid + "/f" + i);
                Minecraft.getInstance().getTextureManager().register(id, dyn);

                int delayCs = Math.max(1, gif.getDelay(i));      // hundredths (cs)
                int rawMs   = delayCs * 10;
                int clamped = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, rawMs));
                int spedUp  = (int)Math.max(MIN_DELAY_MS, Math.round(clamped / SPEED_MULTIPLIER));

                list.add(new CapeFrame(id, spedUp));
            }

            data.setAnimated(list);
        } catch (OutOfMemoryError oom) {
            data.setStatic(null);
        } catch (Throwable t) {
            data.setStatic(null);
        }
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
        private volatile ResourceLocation current;
        private List<CapeFrame> frames;
        private int idx;
        private long nextAtMs;

        CapeData(UUID uuid) { this.uuid = uuid; }

        void setStatic(ResourceLocation id) {
            this.frames = null;
            this.current = id;
        }

        void setAnimated(List<CapeFrame> frames) {
            this.frames = frames;
            this.idx = 0;
            this.current = frames.get(0).id();
            this.nextAtMs = System.currentTimeMillis() + frames.get(0).delayMs();
        }

        ResourceLocation currentTexture() {
            return current;
        }

        void tick(long now) {
            if (frames == null || frames.isEmpty() || current == null) return;
            if (now >= nextAtMs) {
                idx = (idx + 1) % frames.size();
                CapeFrame f = frames.get(idx);
                current = f.id();
                nextAtMs = now + Math.max(MIN_DELAY_MS, f.delayMs());
            }
        }
    }

    private record CapeFrame(ResourceLocation id, int delayMs) {}
}
