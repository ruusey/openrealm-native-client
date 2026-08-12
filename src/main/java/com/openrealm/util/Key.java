package com.openrealm.util;

public class Key {
    public int presses, absorbs;
    public boolean down, clicked;

    public Key() {
        KeyHandler.keys.add(this);
    }

    public void toggle(boolean pressed) {
        if (pressed != this.down) {
            this.down = pressed;
        }
        if (pressed) {
            this.presses++;
        }
    }

    public void tick() {
        if (this.absorbs < this.presses) {
            this.absorbs++;
            this.clicked = true;
        } else {
            this.clicked = false;
        }
    }
}
