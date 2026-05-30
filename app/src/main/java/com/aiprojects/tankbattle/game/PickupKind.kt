package com.aiprojects.tankbattle.game

/** 地图上随机出现的道具种类 */
enum class PickupKind {
    /** 回复 1 点生命（不超过上限） */
    Repair,

    /** 本局攻击力 +1（上限 5），重生后重置为基础攻击 */
    Power,

    /** 加分 */
    Bonus,
}
