package xyz.nightsync.hdskinssupportgravit.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import xyz.nightsync.hdskinssupportgravit.HDSkinsSupportGravitMod;
import xyz.nightsync.hdskinssupportgravit.utils.GifDecoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CapeManager {

    private CapeManager() {}

    // ============ настройки ============
    private static final String API_FMT = "https://nightsync.xyz/api/minecraft/getcape/%s";

    // скорость анимации (множитель): 2.0 = быстрее в 2 раза, 0.5 = в 2 раза медленнее
    private static volatile double SPEED_MULTIPLIER = 3.0;
    public static void setSpeed(double mul) { SPEED_MULTIPLIER = Math.max(0.1, mul); }

    // анти-краш лимиты
    private static final int MAX_W = 1024;
    private static final int MAX_H = 1024;
    private static final int MAX_FRAMES = 240;
    private static final long MAX_TOTAL_PIXELS = 32_000_000L; // под стриминг можно больше
    private static final int MAX_BYTES = 20 * 1024 * 1024;     // 20 MB максимум качаем
    private static final int MIN_DELAY_MS = 15;
    private static final int MAX_DELAY_MS = 5000;

    // кэширование
    private static final int MAX_CAPES = 64; // LRU по игрокам
//    private static final long TTL_MS = 10 * 60_000L; // авто-обновление раз в 10 минут

    // ============ состояние ============
    // LRU с авто-эвиктом (release на render-потоке)
    private static final Map<UUID, CapeData> CAPES = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, CapeData> e) {
                    if (size() > MAX_CAPES) {
                        CapeData cd = e.getValue();
                        if (cd != null) cd.releaseAll();
                        return true;
                    }
                    return false;
                }
            });

    private static final ExecutorService IO_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Cape-IO");
        t.setDaemon(true);
        return t;
    });

    // ================= API =================
    /** Возвращает текущую текстуру плаща игрока (или null, если ещё грузится/не найден). */
//    public static ResourceLocation getCape(UUID uuid) {
//        CapeData data = CAPES.computeIfAbsent(uuid, CapeManager::startLoading);
//        // TTL-обновление без блокировок
//        if (data != null && data.needsRefresh() && data.refreshing.compareAndSet(false, true)) {
//            IO_POOL.submit(() -> {
//                try {
//                    byte[] bytes = httpGet(String.format(API_FMT, uuid));
//                    if (bytes == null || bytes.length < 6) {
//                        data.setStatic(null);
//                        return;
//                    }
//                    if (isGif(bytes)) loadGifCape(data, bytes);
//                    else              loadPngCape(data, bytes);
//                    data.touchLoaded();
//                } catch (Throwable t) {
//                    t.printStackTrace();
//                } finally {
//                    data.refreshing.set(false);
//                }
//            });
//        }
//        return data != null ? data.currentTexture() : null;
//    }
    public static ResourceLocation getCape(UUID uuid) {
        CapeData data = CAPES.computeIfAbsent(uuid, CapeManager::startLoading);
        return data != null ? data.currentTexture() : null;
    }

    /** вызывать из client-тика */
    public static void clientTick() {
        long now = System.currentTimeMillis();
        synchronized (CAPES) {
            for (CapeData cd : CAPES.values()) {
                cd.tick(now);
            }
        }
    }

    // ================= загрузка =================

    private static CapeData startLoading(UUID uuid) {
        CapeData data = new CapeData(uuid);
        IO_POOL.submit(() -> {
            try {
                byte[] bytes = httpGet(String.format(API_FMT, uuid));
                if (bytes == null || bytes.length < 6) {
                    data.setStatic(null);
                    return;
                }
                if (isGif(bytes)) loadGifCape(data, bytes);
                else              loadPngCape(data, bytes);
//                data.touchLoaded();
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

    private static byte[] httpGet(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(url).toURL().openConnection();
        c.setConnectTimeout((int) Duration.ofSeconds(7).toMillis());
        c.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        c.setRequestMethod("GET");
        c.addRequestProperty("User-Agent", HDSkinsSupportGravitMod.MODID + "/1.0");

        int code = c.getResponseCode();
        if (code != 200) return null;

        int len = c.getContentLength();
        if (len > 0 && len > MAX_BYTES) return null;

        try (InputStream in = c.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, Math.min(len > 0 ? len : 0, MAX_BYTES)))) {

            byte[] buf = new byte[8192];
            int read, total = 0;
            while ((read = in.read(buf)) != -1) {
                total += read;
                if (total > MAX_BYTES) return null;
                out.write(buf, 0, read);
            }
            return out.toByteArray();
        }
    }

    // --- PNG ---
    private static void loadPngCape(CapeData data, byte[] bytes) throws IOException {
        final NativeImage img = NativeImage.read(new ByteArrayInputStream(bytes));
        final int w = img.getWidth();
        final int h = img.getHeight();
        if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H) {
            try { img.close(); } catch (Throwable ignored) {}
            data.setStatic(null);
            return;
        }
        RenderSystem.recordRenderCall(() -> {
            try {
                DynamicTexture dyn = new DynamicTexture(img); // img теперь владение у текстуры
                ((AbstractTexture) dyn).setFilter(false, false); // без blur/mip
                ResourceLocation id = rl("cape/" + data.uuid + "/static");
                Minecraft.getInstance().getTextureManager().register(id, dyn);
                data.setStatic(id);
            } catch (Throwable t) {
                try { img.close(); } catch (Throwable ignored) {}
                t.printStackTrace();
                data.setStatic(null);
            }
        });
    }

    // --- GIF (стриминг в одну текстуру) ---
    private static void loadGifCape(CapeData data, byte[] bytes) throws IOException {
        GifDecoder.GifImage gif = GifDecoder.read(new ByteArrayInputStream(bytes));
        // если есть метод setCacheFrames(false) — выключим кэш кадров
        try { gif.getClass().getMethod("setCacheFrames", boolean.class).invoke(gif, false); } catch (Throwable ignored) {}

        int w = gif.getWidth(), h = gif.getHeight();
        if (w <= 0 || h <= 0 || w > MAX_W || h > MAX_H) { data.setStatic(null); return; }

        int framesAll = gif.getFrameCount();
        if (framesAll <= 0) { data.setStatic(null); return; }

        int frames = Math.min(framesAll, MAX_FRAMES);
        long totalAll = 1L * w * h * frames;
        if (totalAll > MAX_TOTAL_PIXELS) {
            frames = (int) Math.max(1, MAX_TOTAL_PIXELS / Math.max(1L, w * (long) h));
        }

        int[] delaysMs = new int[frames];
        for (int i = 0; i < frames; i++) {
            int delayCs = Math.max(1, gif.getDelay(i));
            int rawMs   = delayCs * 10;
            int clamped = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, rawMs));
            delaysMs[i] = (int) Math.max(MIN_DELAY_MS, Math.round(clamped / SPEED_MULTIPLIER));
        }

        // создать текстуру и зарегистрировать — на render-потоке
        final int fw = w, fh = h;
        final int fFrames = frames;
        RenderSystem.recordRenderCall(() -> {
            try {
                DynamicTexture dyn = new DynamicTexture(fw, fh, true);
                ((AbstractTexture) dyn).setFilter(false, false); // без blur/mip
                ResourceLocation id = rl("cape/" + data.uuid + "/anim");
                Minecraft.getInstance().getTextureManager().register(id, dyn);
                data.setAnimatedStreaming(gif, dyn, id, Arrays.copyOf(delaysMs, fFrames));
            } catch (Throwable t) {
                t.printStackTrace();
                data.setStatic(null);
            }
        });
    }

    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(HDSkinsSupportGravitMod.MODID, path.replaceAll("[^a-zA-Z0-9/_.-]", "_"));
    }

    // ================= model =================
    private static final class CapeData {
        final UUID uuid;

        // static
        private ResourceLocation staticTex;

        // anim (streaming)
        private GifDecoder.GifImage gif;
        private DynamicTexture dyn;
        private ResourceLocation dynId;
        private int[] delays;
        private int idx = -1;
        private long nextAt = 0L;

        // refresh/ttl
//        private volatile long loadedAt = 0L;
//        final AtomicBoolean refreshing = new AtomicBoolean(false);

        CapeData(UUID uuid) { this.uuid = uuid; }

        ResourceLocation currentTexture() {
            return dynId != null ? dynId : staticTex;
        }

        void setStatic(ResourceLocation id) {
            this.gif = null; this.dyn = null; this.dynId = null; this.delays = null;
            this.staticTex = id; this.idx = -1; this.nextAt = 0L;
        }

        void setAnimatedStreaming(GifDecoder.GifImage gif, DynamicTexture dyn, ResourceLocation id, int[] delays) {
            this.gif = gif; this.dyn = dyn; this.dynId = id; this.delays = delays;
            this.staticTex = null; this.idx = -1; this.nextAt = 0L;
        }

        void tick(long now) {
            if (dyn == null || gif == null || delays == null) return;
            if (idx == -1 || now >= nextAt) {
                idx = (idx + 1) % delays.length;

                final java.awt.image.BufferedImage bi = renderFrameNoCacheSafe(gif, idx);
                if (bi != null) {
                    try {
                        final NativeImage ni = dyn.getPixels(); // CPU буфер
                        final int w = ni.getWidth(), h = ni.getHeight();
                        final int[] argb = new int[w * h];
                        bi.getRGB(0, 0, w, h, argb, 0, w);
                        // ARGB -> ABGR
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
                        // загрузка в GPU — строго на render-потоке
                        RenderSystem.recordRenderCall(dyn::upload);
                    } catch (Throwable t) {
                        // если что-то пошло не так — вырубаем плащ, чтобы не крашить клиент
                        disable();
                    } finally {
                        bi.flush();
                    }
                }

                nextAt = now + Math.max(MIN_DELAY_MS, delays[idx]);
            }
        }

        private void disable() {
            if (dynId != null) {
                ResourceLocation id = dynId;
                RenderSystem.recordRenderCall(() ->
                        Minecraft.getInstance().getTextureManager().release(id));
            }
            this.gif = null; this.dyn = null; this.dynId = null; this.delays = null;
            this.staticTex = null;
        }

        void releaseAll() {
            if (dynId != null) {
                ResourceLocation id = dynId;
                RenderSystem.recordRenderCall(() ->
                        Minecraft.getInstance().getTextureManager().release(id));
                dynId = null; dyn = null; gif = null; delays = null;
            }
            if (staticTex != null) {
                ResourceLocation id = staticTex;
                RenderSystem.recordRenderCall(() ->
                        Minecraft.getInstance().getTextureManager().release(id));
                staticTex = null;
            }
        }

//        void touchLoaded() { loadedAt = System.currentTimeMillis(); }
//        boolean needsRefresh() { return (System.currentTimeMillis() - loadedAt) > TTL_MS; }
    }

    // безопасный вызов без кэша (если есть метод в твоём декодере)
    private static java.awt.image.BufferedImage renderFrameNoCacheSafe(GifDecoder.GifImage gif, int index) {
        try {
            var m = gif.getClass().getMethod("renderFrameNoCache", int.class);
            return (java.awt.image.BufferedImage) m.invoke(gif, index);
        } catch (Throwable e) {
            // fallback на обычный getFrame (может кэшировать внутри — не страшно, мы не держим ссылки)
            return gif.getFrame(index);
        }
    }
}
