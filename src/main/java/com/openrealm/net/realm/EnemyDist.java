package com.openrealm.net.realm;

import com.openrealm.game.entity.Enemy;

final class EnemyDist {
    final Enemy enemy;
    final float distSq;
    EnemyDist(Enemy e, float d) { this.enemy = e; this.distSq = d; }
}
