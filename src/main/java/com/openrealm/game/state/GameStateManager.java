package com.openrealm.game.state;

import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.openrealm.game.OpenRealmGame;
import com.openrealm.game.graphics.SpriteSheet;
import com.openrealm.game.math.Rectangle;
import com.openrealm.game.math.Vector2f;
import com.openrealm.util.Camera;
import com.openrealm.util.KeyHandler;
import com.openrealm.util.MouseHandler;

public class GameStateManager {

    private GameState states[];

    public static Vector2f map;

    public static final int MENU = 0;
    public static final int PLAY = 1;
    public static final int PAUSE = 2;
    public static final int GAMEOVER = 3;
    // Pre-game flow added when porting the web client's account UI to LibGDX.
    // LOGIN sits in slot 4 and CHARSELECT sits in slot 5; PLAY is no longer
    // auto-instantiated, so the launcher boots into LOGIN and only constructs
    // a PlayState once the user actually clicks "Play".
    public static final int LOGIN = 4;
    public static final int CHARSELECT = 5;

    public static SpriteSheet ui;
    public static SpriteSheet button;
    public static Camera cam;

    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private BitmapFont defaultFont;
    private OrthographicCamera camera;

    public GameStateManager(SpriteBatch batch, ShapeRenderer shapes, BitmapFont defaultFont, OrthographicCamera camera) {
        this.batch = batch;
        this.shapes = shapes;
        this.defaultFont = defaultFont;
        this.camera = camera;

        GameStateManager.map = new Vector2f(OpenRealmGame.width, OpenRealmGame.height);
        Vector2f.setWorldVar(GameStateManager.map.x, GameStateManager.map.y);

        this.states = new GameState[8];

        GameStateManager.ui = new SpriteSheet("ui.png", 64, 64);
        GameStateManager.button = new SpriteSheet("buttons.png", 122, 57);

        GameStateManager.cam = new Camera(
                new Rectangle(new Vector2f(0, 0), OpenRealmGame.width + 64, OpenRealmGame.height + 64));

        // Boot path:
        //   - If GameLauncher's CLI form supplied creds + characterUuid, jump
        //     straight into PlayState (used by automation).
        //   - Otherwise show the login screen and let the user pick a flow.
        boolean cliCreds = com.openrealm.net.client.SocketClient.PLAYER_EMAIL != null
                && com.openrealm.net.client.SocketClient.PLAYER_PASSWORD != null
                && com.openrealm.net.client.SocketClient.CHARACTER_UUID != null;
        if (cliCreds) {
            this.add(GameStateManager.PLAY);
        } else {
            this.add(GameStateManager.LOGIN, new LoginState(this));
        }
    }

    public boolean isStateActive(int state) {
        return this.states[state] != null;
    }

    public GameState getState(int state) {
        return this.states[state];
    }

    public void pop(int state) {
        this.states[state] = null;
    }

    public PlayState getPlayState() {
        return (PlayState) this.states[GameStateManager.PLAY];
    }

    public void add(int state) {
        if (this.states[state] != null)
            return;

        switch (state) {
        case GameStateManager.PLAY:
            GameStateManager.cam = new Camera(
                    new Rectangle(new Vector2f(0, 0), OpenRealmGame.width + 64, OpenRealmGame.height + 64));
            this.states[GameStateManager.PLAY] = new PlayState(this, GameStateManager.cam);
            break;
        case GameStateManager.PAUSE:
            this.states[GameStateManager.PAUSE] = new PauseState(this, null);
            break;
        case GameStateManager.GAMEOVER:
            this.states[GameStateManager.GAMEOVER] = new GameOverState(this);
            break;
        default:
            break;
        }
    }

    public void add(int state, GameState gameState) {
        if (this.states[state] != null)
            return;
        // Generic slot assignment so we don't have to grow the switch every
        // time a new state is introduced. Bounds-check is implicit because
        // the array length is fixed at construction.
        this.states[state] = gameState;
    }

    public void addAndpop(int state) {
        this.addAndpop(state, 0);
    }

    public void addAndpop(int state, int remove) {
        this.pop(state);
        this.add(state);
    }

    public void update(double time) {
        for (int i = 0; i < this.states.length; i++) {
            if (this.states[i] != null) {
                this.states[i].update(time);
            }
        }
    }

    public void input(MouseHandler mouse, KeyHandler key) {
        for (int i = 0; i < this.states.length; i++) {
            if (this.states[i] != null) {
                this.states[i].input(mouse, key);
            }
        }
    }

    public void render(SpriteBatch batch, ShapeRenderer shapes, BitmapFont font) {
        for (int i = 0; i < this.states.length; i++) {
            if (this.states[i] != null) {
                this.states[i].render(batch, shapes, font);
            }
        }
    }

    public OrthographicCamera getCamera() {
        return this.camera;
    }
}
