package com.openrealm.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;

public class MouseHandler {

    private static volatile int mouseX = -1;
    private static volatile int mouseY = -1;
    private static volatile int[] mouseButtonStates = new int[] { -1, -1, -1 };

    public MouseHandler() {
    }

    public void update() {
        MouseHandler.mouseX = Gdx.input.getX();
        MouseHandler.mouseY = Gdx.input.getY();

        // Stored in AWT button order: index 0=left, 1=middle, 2=right.
        MouseHandler.mouseButtonStates[0] = Gdx.input.isButtonPressed(Input.Buttons.LEFT) ? 1 : -1;
        MouseHandler.mouseButtonStates[1] = Gdx.input.isButtonPressed(Input.Buttons.MIDDLE) ? 1 : -1;
        MouseHandler.mouseButtonStates[2] = Gdx.input.isButtonPressed(Input.Buttons.RIGHT) ? 1 : -1;
    }

    public int getX() {
        return MouseHandler.mouseX;
    }

    public int getY() {
        return MouseHandler.mouseY;
    }

    /**
     * @param mouseButton AWT-style button number: 1=LEFT, 2=MIDDLE, 3=RIGHT
     */
    public boolean isPressed(int mouseButton) {
        if ((mouseButton - 1) < 0)
            return false;
        return MouseHandler.mouseButtonStates[mouseButton - 1] > -1;
    }
}
