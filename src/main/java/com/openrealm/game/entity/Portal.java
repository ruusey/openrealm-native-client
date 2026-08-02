package com.openrealm.game.entity;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import java.time.Instant;

import com.openrealm.game.data.GameDataManager;
import com.openrealm.game.data.GameSpriteManager;
import com.openrealm.game.graphics.Sprite;
import com.openrealm.game.math.Vector2f;
import com.openrealm.net.Streamable;
import com.openrealm.net.realm.Realm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Streamable
public class Portal {
    private static final float OUTLINE_OFFSET = 1f;
    private static final float OUTLINE_ALPHA = 0.85f;

    private long id;
    private short portalId;
    private long fromRealmId;
    private long toRealmId;
    private long expires;
    private Vector2f pos;
    private Sprite sprite;
    private String targetNodeId;
    // Target-realm summary shown under the portal; populated from NetPortal on the wire.
    private String targetLabel;
    private float targetDifficulty;
    private int targetPlayerCount;
    private long targetPurificationProgress;
    private long targetPurificationGoal;


    public Portal(long id, short portalId, Vector2f pos) {
        this.id = id;
        this.portalId = portalId;
        this.fromRealmId = 0;
        this.toRealmId = 0;
        this.pos = pos;
        this.expires = Instant.now().toEpochMilli() + 35000;
        this.sprite = GameSpriteManager.loadSprite(GameDataManager.PORTALS.get((int) portalId));
    }

    public Portal(short portalId, Vector2f pos) {
        this.portalId = portalId;
        this.pos = pos;
        this.fromRealmId = 0;
        this.toRealmId = 0;
        this.expires = Instant.now().toEpochMilli() + 35000;
        this.sprite = GameSpriteManager.loadSprite(GameDataManager.PORTALS.get((int) portalId));
    }

    public void linkPortal(Realm from, Realm to) {
        if (from != null) {
            this.setFromRealm(from);
        }
        if (to != null) {
            this.setToRealm(to);
        }
    }

    private void setFromRealm(Realm from) {
        this.setFromRealm(from.getRealmId());
    }

    private void setToRealm(Realm to) {
        this.setToRealm(to.getRealmId());
    }

    private void setFromRealm(long fromRealmId) {
        this.fromRealmId = fromRealmId;
    }

    private void setToRealm(long toRealmId) {
        this.toRealmId = toRealmId;
    }

    public short getPortalId() {
        return this.portalId;
    }

    public boolean isExpired() {
        return Instant.now().toEpochMilli() >= this.expires;
    }

    public void setNeverExpires() {
        this.expires = Long.MAX_VALUE;
    }

    public boolean equals(Portal other) {
        return (this.id == other.getId()) && (this.portalId == other.getPortalId())
                && this.fromRealmId == other.getFromRealmId() && this.getToRealmId() == other.getToRealmId()
                && this.getPos().equals(other.getPos()) && (this.expires == other.getExpires());
    }

    public void render(SpriteBatch batch) {
        if (this.sprite != null && this.sprite.getRegion() != null) {
            final TextureRegion region = this.sprite.getRegion();
            final float bx = this.pos.getWorldVar().x;
            final float by = this.pos.getWorldVar().y;
            // Dark silhouette outline (matches the in-world sprite stroke): 8
            // offset tinted copies (4 cardinal + 4 diagonal) behind the portal,
            // then the portal on top. Diagonals fill the missed corner pixels.
            final float prevColor = batch.getPackedColor();
            batch.setColor(0f, 0f, 0f, OUTLINE_ALPHA);
            batch.draw(region, bx + OUTLINE_OFFSET, by,                 32, 32);
            batch.draw(region, bx - OUTLINE_OFFSET, by,                 32, 32);
            batch.draw(region, bx,                 by + OUTLINE_OFFSET, 32, 32);
            batch.draw(region, bx,                 by - OUTLINE_OFFSET, 32, 32);
            batch.draw(region, bx + OUTLINE_OFFSET, by + OUTLINE_OFFSET, 32, 32);
            batch.draw(region, bx + OUTLINE_OFFSET, by - OUTLINE_OFFSET, 32, 32);
            batch.draw(region, bx - OUTLINE_OFFSET, by + OUTLINE_OFFSET, 32, 32);
            batch.draw(region, bx - OUTLINE_OFFSET, by - OUTLINE_OFFSET, 32, 32);
            batch.setPackedColor(prevColor);
            batch.draw(region, bx, by, 32, 32);
        }
    }
}
