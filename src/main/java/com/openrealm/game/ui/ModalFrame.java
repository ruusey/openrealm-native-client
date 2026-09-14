package com.openrealm.game.ui;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;

/** Shared modal-window chrome: dim backdrop, centered panel + header strip, and
 *  the header Cancel button geometry that render() and click hit-testing must share.
 *  Drawing helpers assume the caller already holds an active Filled shape pass; the
 *  standalone {@link #drawBackdrop} opens its own pass for windows that need it alone. */
public final class ModalFrame {

    // Standard header height + Cancel button metrics shared by shop/storage dialogs.
    public static final int HEADER_HEIGHT = 32;
    private static final int CLOSE_BUTTON_WIDTH = 60;
    private static final int CLOSE_BUTTON_MARGIN_RIGHT = 6;
    private static final int CLOSE_BUTTON_MARGIN_TOP = 4;
    private static final int CLOSE_BUTTON_HEIGHT_INSET = 8;

    private ModalFrame() {
    }

    /** Dim full-screen backdrop; opens and closes its own Filled shape pass. */
    public static void drawBackdrop(ShapeRenderer shapes, int screenWidth, int screenHeight) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        drawBackdropInPass(shapes, screenWidth, screenHeight);
        shapes.end();
    }

    /** Dim backdrop drawn into an already-active Filled shape pass. */
    public static void drawBackdropInPass(ShapeRenderer shapes, int screenWidth, int screenHeight) {
        shapes.setColor(0f, 0f, 0f, 0.65f);
        shapes.rect(0, 0, screenWidth, screenHeight);
    }

    /** Centered dialog panel fill plus the darker header strip, into an active Filled pass. */
    public static void drawPanel(ShapeRenderer shapes, int x, int y, int width, int height, int headerHeight) {
        shapes.setColor(0.10f, 0.10f, 0.12f, 0.97f);
        shapes.rect(x, y, width, height);
        shapes.setColor(0.06f, 0.06f, 0.08f, 1f);
        shapes.rect(x, y, width, headerHeight);
    }

    /** Header Cancel button, into an active Filled pass, using {@link #closeButtonBounds}. */
    public static void drawCloseButton(ShapeRenderer shapes, int x, int y, int width, int headerHeight) {
        final Rectangle bounds = closeButtonBounds(x, y, width, headerHeight);
        shapes.setColor(0.40f, 0.20f, 0.20f, 1f);
        shapes.rect(bounds.getPos().x, bounds.getPos().y, bounds.getWidth(), bounds.getHeight());
    }

    /** Single source of truth for the header Cancel button rect, shared by render and hit-test. */
    public static Rectangle closeButtonBounds(int x, int y, int width, int headerHeight) {
        final int buttonWidth = CLOSE_BUTTON_WIDTH;
        final int buttonHeight = headerHeight - CLOSE_BUTTON_HEIGHT_INSET;
        final int buttonX = x + width - buttonWidth - CLOSE_BUTTON_MARGIN_RIGHT;
        final int buttonY = y + CLOSE_BUTTON_MARGIN_TOP;
        return new Rectangle(new Vector2f(buttonX, buttonY), buttonWidth, buttonHeight);
    }

    /** Top-down point-in-rect test for a modal button rect. */
    public static boolean contains(Rectangle bounds, int mouseX, int mouseY) {
        final float bx = bounds.getPos().x;
        final float by = bounds.getPos().y;
        return mouseX >= bx && mouseX <= bx + bounds.getWidth()
                && mouseY >= by && mouseY <= by + bounds.getHeight();
    }
}
