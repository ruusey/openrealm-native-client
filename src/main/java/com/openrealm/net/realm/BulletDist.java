package com.openrealm.net.realm;

import com.openrealm.game.entity.Bullet;

final class BulletDist {
    final Bullet bullet;
    final float distSq;
    BulletDist(Bullet b, float d) { this.bullet = b; this.distSq = d; }
}
