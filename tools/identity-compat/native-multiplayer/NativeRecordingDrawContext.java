package dev.lanminecraft.aotqa.discovery;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.class_310;
import net.minecraft.class_327;
import net.minecraft.class_332;
import net.minecraft.class_2561;
import net.minecraft.class_4597;

/** Captures the actual native row renderer's text arguments without GPU draws. */
public final class NativeRecordingDrawContext extends class_332 {
    public final List<String> text = new ArrayList<>();
    public NativeRecordingDrawContext(class_310 client, class_4597.class_4598 buffers) { super(client, buffers); }
    @Override public int method_51439(class_327 font, class_2561 value, int x, int y, int color, boolean shadow) {
        text.add(value.getString()); return 0;
    }
    @Override public int method_51433(class_327 font, String value, int x, int y, int color, boolean shadow) {
        text.add(value); return 0;
    }
}
