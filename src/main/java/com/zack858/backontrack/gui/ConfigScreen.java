package com.zack858.backontrack.gui;

import com.zack858.backontrack.config.ModConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Vanilla-styled settings screen. Two columns of (label, widget) pairs.
 * Saves config on confirm, returns to parent screen on cancel.
 */
public class ConfigScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int COL_WIDTH = 200;
    private static final int FIELD_WIDTH = 110;
    private static final int LEFT_MARGIN = 60;

    private final Screen parent;
    private final ModConfig draft;

    private TextFieldWidget widthField;
    private TextFieldWidget heightField;
    private TextFieldWidget fpsField;
    private TextFieldWidget bitrateField;
    private TextFieldWidget audioBitrateField;
    private TextFieldWidget ffmpegPathField;
    private TextFieldWidget outputDirField;

    public ConfigScreen(Screen parent) {
        super(Text.translatable("back_on_track.config.title"));
        this.parent = parent;
        this.draft = copy(ModConfig.get());
    }

    @Override
    protected void init() {
        int startY = 36;
        int leftCol = this.width / 2 - COL_WIDTH;
        int rightCol = this.width / 2 + 20;

        // ---- Left column (numeric / text fields) ----
        widthField = addField(leftCol, startY,
                String.valueOf(draft.width), 6,
                v -> draft.width = parsePositiveInt(v, draft.width));
        heightField = addField(leftCol, startY + ROW_HEIGHT,
                String.valueOf(draft.height), 6,
                v -> draft.height = parsePositiveInt(v, draft.height));
        fpsField = addField(leftCol, startY + ROW_HEIGHT * 2,
                String.valueOf(draft.fps), 4,
                v -> draft.fps = clamp(parsePositiveInt(v, draft.fps), 1, 240));
        bitrateField = addField(leftCol, startY + ROW_HEIGHT * 3,
                String.valueOf(draft.bitrateKbps), 6,
                v -> draft.bitrateKbps = clamp(parsePositiveInt(v, draft.bitrateKbps), 250, 200_000));
        ffmpegPathField = addField(leftCol, startY + ROW_HEIGHT * 4,
                draft.ffmpegPath, 256,
                v -> draft.ffmpegPath = v.isEmpty() ? "ffmpeg" : v);
        outputDirField = addField(leftCol, startY + ROW_HEIGHT * 5,
                draft.outputDir, 256,
                v -> draft.outputDir = v.isEmpty() ? "recordings" : v);

        // ---- Right column (cycling enums + booleans) ----
        addCycling(rightCol, startY,
                Text.translatable("back_on_track.config.codec"),
                List.of("libx264", "libx265", "h264_nvenc", "hevc_nvenc", "h264_qsv", "h264_vaapi"),
                draft.videoCodec,
                v -> draft.videoCodec = v);

        addCycling(rightCol, startY + ROW_HEIGHT,
                Text.translatable("back_on_track.config.preset"),
                List.of("ultrafast", "superfast", "veryfast", "faster", "fast", "medium", "slow"),
                draft.preset,
                v -> draft.preset = v);

        addCycling(rightCol, startY + ROW_HEIGHT * 2,
                Text.translatable("back_on_track.config.container"),
                List.of("mp4", "mkv", "mov"),
                draft.container,
                v -> draft.container = v);

        addToggle(rightCol, startY + ROW_HEIGHT * 3,
                Text.translatable("back_on_track.config.captureGui"),
                draft.captureGui,
                v -> draft.captureGui = v);

        addToggle(rightCol, startY + ROW_HEIGHT * 4,
                Text.translatable("back_on_track.config.audio"),
                draft.captureAudio,
                v -> draft.captureAudio = v);

        addToggle(rightCol, startY + ROW_HEIGHT * 5,
                Text.translatable("back_on_track.config.voicechat"),
                draft.captureVoiceChat,
                v -> draft.captureVoiceChat = v);

        audioBitrateField = addField(rightCol, startY + ROW_HEIGHT * 6,
                String.valueOf(draft.audioBitrateKbps), 4,
                v -> draft.audioBitrateKbps = clamp(parsePositiveInt(v, draft.audioBitrateKbps), 32, 512));

        addToggle(rightCol, startY + ROW_HEIGHT * 7,
                Text.translatable("back_on_track.config.showHud"),
                draft.showHud,
                v -> draft.showHud = v);

        // ---- Save / Cancel ----
        int btnY = this.height - 30;
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("back_on_track.config.save"),
                        b -> applyAndClose())
                .dimensions(this.width / 2 - 154, btnY, 150, 20)
                .build());
        addDrawableChild(ButtonWidget.builder(
                        Text.translatable("back_on_track.config.cancel"),
                        b -> this.client.setScreen(parent))
                .dimensions(this.width / 2 + 4, btnY, 150, 20)
                .build());
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        ctx.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);

        int startY = 36;
        int leftLabel = this.width / 2 - COL_WIDTH - LEFT_MARGIN;
        int rightLabel = this.width / 2 + 20 - LEFT_MARGIN;

        // Field labels (left column).
        ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("back_on_track.config.resolution").copy().append(" W"),
                leftLabel, startY + 6, 0xCCCCCC);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("back_on_track.config.resolution").copy().append(" H"),
                leftLabel, startY + ROW_HEIGHT + 6, 0xCCCCCC);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("back_on_track.config.fps"),
                leftLabel, startY + ROW_HEIGHT * 2 + 6, 0xCCCCCC);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("back_on_track.config.bitrate"),
                leftLabel, startY + ROW_HEIGHT * 3 + 6, 0xCCCCCC);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("back_on_track.config.ffmpegPath"),
                leftLabel, startY + ROW_HEIGHT * 4 + 6, 0xCCCCCC);
        ctx.drawTextWithShadow(this.textRenderer,
                Text.translatable("back_on_track.config.outputDir"),
                leftLabel, startY + ROW_HEIGHT * 5 + 6, 0xCCCCCC);

        // Audio bitrate label sits in the right column.
        ctx.drawTextWithShadow(this.textRenderer,
                Text.literal("Audio kbps").formatted(Formatting.GRAY),
                rightLabel, startY + ROW_HEIGHT * 6 + 6, 0xCCCCCC);
    }

    // ---- helpers ----

    private TextFieldWidget addField(int x, int y, String initial, int maxLen,
                                     java.util.function.Consumer<String> onChange) {
        TextFieldWidget f = new TextFieldWidget(this.textRenderer, x, y, FIELD_WIDTH, 20, Text.empty());
        f.setMaxLength(Math.max(maxLen, initial.length()));
        f.setText(initial);
        f.setChangedListener(onChange);
        addDrawableChild(f);
        return f;
    }

    private <T> void addCycling(int x, int y, Text label, List<T> values, T current,
                                java.util.function.Consumer<T> onChange) {
        CyclingButtonWidget<T> btn = CyclingButtonWidget.<T>builder(
                        v -> Text.literal(String.valueOf(v)), current)
                .values(values)
                .build(x, y, FIELD_WIDTH, 20, label, (b, v) -> onChange.accept(v));
        addDrawableChild(btn);
    }

    private void addToggle(int x, int y, Text label, boolean current,
                           java.util.function.Consumer<Boolean> onChange) {
        CyclingButtonWidget<Boolean> btn = CyclingButtonWidget.onOffBuilder(current)
                .build(x, y, FIELD_WIDTH, 20, label, (b, v) -> onChange.accept(v));
        addDrawableChild(btn);
    }

    private void applyAndClose() {
        // Re-parse text fields one final time (in case of pending edits).
        if (widthField != null) draft.width = parsePositiveInt(widthField.getText(), draft.width);
        if (heightField != null) draft.height = parsePositiveInt(heightField.getText(), draft.height);
        if (fpsField != null) draft.fps = clamp(parsePositiveInt(fpsField.getText(), draft.fps), 1, 240);
        if (bitrateField != null) draft.bitrateKbps = clamp(parsePositiveInt(bitrateField.getText(), draft.bitrateKbps), 250, 200_000);
        if (audioBitrateField != null) draft.audioBitrateKbps = clamp(parsePositiveInt(audioBitrateField.getText(), draft.audioBitrateKbps), 32, 512);
        if (ffmpegPathField != null) {
            String v = ffmpegPathField.getText();
            draft.ffmpegPath = v.isEmpty() ? "ffmpeg" : v;
        }
        if (outputDirField != null) {
            String v = outputDirField.getText();
            draft.outputDir = v.isEmpty() ? "recordings" : v;
        }
        copyInto(draft, ModConfig.get());
        ModConfig.save();
        this.client.setScreen(parent);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    private static int parsePositiveInt(String s, int fallback) {
        try {
            int v = Integer.parseInt(s.trim());
            return v > 0 ? v : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static ModConfig copy(ModConfig src) {
        ModConfig dst = new ModConfig();
        copyInto(src, dst);
        return dst;
    }

    private static void copyInto(ModConfig src, ModConfig dst) {
        dst.width = src.width;
        dst.height = src.height;
        dst.fps = src.fps;
        dst.bitrateKbps = src.bitrateKbps;
        dst.videoCodec = src.videoCodec;
        dst.preset = src.preset;
        dst.container = src.container;
        dst.captureGui = src.captureGui;
        dst.captureAudio = src.captureAudio;
        dst.captureVoiceChat = src.captureVoiceChat;
        dst.audioSampleRate = src.audioSampleRate;
        dst.audioChannels = src.audioChannels;
        dst.audioBitrateKbps = src.audioBitrateKbps;
        dst.ffmpegPath = src.ffmpegPath;
        dst.outputDir = src.outputDir;
        dst.showHud = src.showHud;
    }
}
