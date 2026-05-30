package com.aiprojects.tankbattle.game

enum class Direction {
    Up,
    Down,
    Left,
    Right,
    ;

    fun dx(): Float =
        when (this) {
            Left -> -1f
            Right -> 1f
            else -> 0f
        }

    fun dy(): Float =
        when (this) {
            Up -> -1f
            Down -> 1f
            else -> 0f
        }
}

enum class Tile {
    Empty,
    Brick,
    Steel,
    Base,
}

enum class Phase {
    Playing,
    Won,
    Lost,
}

enum class BulletOwner {
    Player,
    Enemy,
}

data class Tank(
    val id: Int,
    val x: Float,
    val y: Float,
    val facing: Direction,
    val cooldown: Float,
    val alive: Boolean,
    /** 当前生命；玩家与敌车均使用 */
    val hp: Int,
    val maxHp: Int,
    /** 子弹伤害 */
    val attack: Int,
    /** 非空表示敌车类型；玩家为 null */
    val enemyType: EnemyTankType? = null,
)

data class Bullet(
    val id: Int,
    val x: Float,
    val y: Float,
    val facing: Direction,
    val owner: BulletOwner,
    val damage: Int,
)

data class Pickup(
    val id: Int,
    val x: Float,
    val y: Float,
    val kind: PickupKind,
)

enum class ExplosionKind {
    /** 砖墙、钢墙被击中 */
    Spark,

    /** 坦克被击毁、玩家受伤 */
    Tank,

    /** 基地被摧毁 */
    Base,
}

data class ExplosionFx(
    /** 地块坐标系：格中心为 x+0.5 */
    val x: Float,
    val y: Float,
    val kind: ExplosionKind,

    /** 归一化进度 0..1 */
    val t: Float,
)

data class GameSnapshot(
    val tiles: List<List<Tile>>,
    val cols: Int,
    val rows: Int,
    val player: Tank,
    val enemies: List<Tank>,
    val bullets: List<Bullet>,
    val pickups: List<Pickup>,
    val explosions: List<ExplosionFx>,
    val lives: Int,
    val score: Int,
    val enemiesRemaining: Int,
    val phase: Phase,
    val spawnPoints: List<Pair<Float, Float>>,
    val playerSpawn: Pair<Float, Float>,
    /** 当前关卡序号，从 1 开始 */
    val levelDisplayIndex: Int,
    val totalLevels: Int,
    val levelTitle: String,
)
