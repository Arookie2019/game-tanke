package com.aiprojects.tankbattle.game

import kotlin.random.Random

/**
 * 五种敌方坦克：血量、攻击力、击破得分、移动速度系数（相对 [GameEngine] 基础敌速）。
 */
enum class EnemyTankType(
    val label: String,
    val maxHp: Int,
    val attack: Int,
    val killScore: Int,
    val speedMul: Float,
) {
    Light("轻型", 1, 1, 85, 1.1f),
    Normal("普通", 2, 1, 105, 1f),
    Heavy("重型", 4, 2, 240, 0.78f),
    Assault("突击", 2, 2, 175, 1.08f),
    Elite("精英", 6, 3, 380, 0.72f),
}

private val ENEMY_SPAWN_WEIGHTS = floatArrayOf(
    0.26f,
    0.34f,
    0.17f,
    0.13f,
    0.10f,
)

fun randomEnemyTankType(random: Random): EnemyTankType {
    val types = EnemyTankType.entries.toTypedArray()
    require(types.size == ENEMY_SPAWN_WEIGHTS.size)
    val r = random.nextFloat()
    var acc = 0f
    for (i in types.indices) {
        acc += ENEMY_SPAWN_WEIGHTS[i]
        if (r <= acc) return types[i]
    }
    return types.last()
}
