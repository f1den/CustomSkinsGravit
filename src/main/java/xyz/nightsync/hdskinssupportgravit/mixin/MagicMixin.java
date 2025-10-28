package xyz.nightsync.hdskinssupportgravit.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.HttpTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(HttpTexture.class)
public class MagicMixin {

    // Достаём private static методы целевого класса
    @Shadow private static void setNoAlpha(NativeImage img, int x1, int y1, int x2, int y2) {}
    @Shadow private static void doNotchTransparencyHack(NativeImage img, int x1, int y1, int x2, int y2) {}

    /**
     * @author Nightly
     * @reason HD Skin Support
     */
    @Overwrite
    private NativeImage processLegacySkin(NativeImage image) {
        final int h = image.getHeight();
        final int w = image.getWidth();

        final int s   = w / 8;
        final int x64 = s * 8;
        final int x32 = s * 4;
        final int x16 = s * 2;
        final int x8  = s;
        final int x4  = s / 2;
        final int x12 = x8 + x4;
        final int x48 = s * 6;
        final int x44 = s * 5;
        final int x52 = x48 + x4;
        final int x48m4 = x48 - x4;

        if (w != x64 || (h != x32 && h != x64)) {
            image.close();
            return null;
        }

        final boolean legacy = h == x32;
        if (legacy) {
            NativeImage newImg = new NativeImage(x64, x64, true);
            newImg.copyFrom(image);
            image.close();
            image = newImg;

            newImg.fillRect(0, x32, x64, x32, 0);

            newImg.copyRect(x4,  x16, x16, x32, x4,  x4,  true, false);
            newImg.copyRect(x8,  x16, x16, x32, x4,  x4,  true, false);
            newImg.copyRect(0,   x16 + x4 + x8, x16 + x8, x32, x4, x12, true, false);
            newImg.copyRect(x4,  x16 + x4 + x8, x16,      x32, x4, x12, true, false);
            newImg.copyRect(x8,  x16 + x4 + x8, x8,       x32, x4, x12, true, false);
            newImg.copyRect(x12, x16 + x4 + x8, x16,      x32, x4, x12, true, false);

            newImg.copyRect(x48m4, x16, -x8,  x32, x4, x4,  true, false);
            newImg.copyRect(x48,   x16, -x8,  x32, x4, x4,  true, false);

            newImg.copyRect(x44, x16 + x4 + x8, 0,   x32, x4, x12, true, false);
            newImg.copyRect(x48m4, x16 + x4 + x8, -x8,  x32, x4, x12, true, false);
            newImg.copyRect(x48,   x16 + x4 + x8, -x16, x32, x4, x12, true, false);
            newImg.copyRect(x52,   x16 + x4 + x8, -x8,  x32, x4, x12, true, false);
        }

        setNoAlpha(image, 0, 0, x32, x16);
        if (legacy) {
            doNotchTransparencyHack(image, x32, 0, x64, x32);
        }
        setNoAlpha(image, 0,  x16, x64, x32);
        setNoAlpha(image, x16, x52 - x4, x52 - x4, x64);
        return image;
    }
}
