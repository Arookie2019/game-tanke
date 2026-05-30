package com.aiprojects.tankbattle.game

/** 游戏内触发的一次性音效事件（由 UI 层用 SoundPool 等播放） */
enum class GameSfx {
    /** 玩家发射 */
    PlayerFire,

    /** 敌车发射（较静） */
    EnemyFire,

    /** 子弹对消、打砖/钢、撞墙火花 */
    Impact,

    /** 坦克爆炸 */
    Explosion,

    /** 玩家被击中 */
    PlayerHit,

    /** 基地被击中 */
    BaseHit,

    /** 通关 */
    LevelWin,

    /** 失败 / 本局结束 */
    GameOver,
}
