package com.openrealm.game.entity;

import com.badlogic.gdx.graphics.g2d.SpriteBatch;
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
    private long id;
    private short portalId;
    private long fromRealmId;
    private long toRealmId;
    private long expires;
    private Vector2f pos;
    private Sprite sprite;
    private String targetNodeId;
    
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
            batch.draw(this.sprite.getRegion(), this.pos.getWorldVar().x, this.pos.getWorldVar().y, 32, 32);
        }
    }

    public void renderOutline(SpriteBatch batch) {
        if (this.sprite == null || this.sprite.getRegion() == null) return;
        final com.badlogic.gdx.graphics.g2d.TextureRegion region = this.sprite.getRegion();
        final int rw = region.getRegionWidth();
        final int rh = region.getRegionHeight();
        if (rw <= 0 || rh <= 0) return;
        final int size = 32;
        final float padX = (float) size / rw;
        final float padY = (float) size / rh;
        batch.draw(region,
                this.pos.getWorldVar().x - padX,
                this.pos.getWorldVar().y - padY,
                size + 2 * padX, size + 2 * padY);
    }
}
