package club.xiaojiawei.hsscript.strategy.phase

import club.xiaojiawei.bean.isValid
import club.xiaojiawei.config.log
import club.xiaojiawei.enums.StepEnum
import club.xiaojiawei.hsscript.bean.Behavior
import club.xiaojiawei.hsscript.bean.DeckStrategyThread
import club.xiaojiawei.hsscript.bean.OutCardThread
import club.xiaojiawei.hsscript.bean.PlayerBehavior
import club.xiaojiawei.hsscript.bean.log.Block
import club.xiaojiawei.hsscript.bean.log.TagChangeEntity
import club.xiaojiawei.hsscript.enums.BlockTypeEnum
import club.xiaojiawei.hsscript.enums.ConfigEnum
import club.xiaojiawei.hsscript.enums.TagEnum
import club.xiaojiawei.hsscript.strategy.AbstractPhaseStrategy
import club.xiaojiawei.hsscript.strategy.DeckStrategyActuator
import club.xiaojiawei.hsscript.utils.ConfigUtil
import club.xiaojiawei.hsscript.utils.GameUtil
import club.xiaojiawei.hsscript.utils.SystemUtil
import club.xiaojiawei.util.isTrue
import club.xiaojiawei.bean.War
import club.xiaojiawei.bean.Player
import club.xiaojiawei.bean.Card

/**
 * 游戏回合阶段
 *
 * @author 肖嘉威
 * @date 2022/11/26 17:24
 */
object GameTurnPhaseStrategy : AbstractPhaseStrategy() {

    private fun checkRivalRobot() {
        log.info { "计算对面" }
        if (rivalBehavior.renewCalcRobotProbability() < 0.1) {
            log.info { "发现对面这个b疑似真人，准备投降，gameId:${war.rival.gameId}" }
            GameUtil.surrender()
        }
        log.info { "对面是脚本概率:" + rivalBehavior.robotProbability }
    }

    private fun checkMeRobot() {
//        log.info { "计算我方" }
//        if (meBehavior.renewCalcRobotProbability() < 0.1) {
//            log.info { "发现自己这个b疑似真人，准备投降，gameId:${war.me.gameId}" }
//        }
//        log.info { "我是脚本概率:" + meBehavior.robotProbability }
    }

    override fun dealTagChangeThenIsOver(line: String, tagChangeEntity: TagChangeEntity): Boolean {
        if (tagChangeEntity.tag == TagEnum.STEP) {
            if (tagChangeEntity.value == StepEnum.MAIN_ACTION.name) {
                if (war.me === war.currentPlayer && war.me.isValid()) {
                    log.info { "我方回合" }
                    val fieldInfo = formatFieldInformation(war)
                    log.info { fieldInfo }
                    cancelAllTask()
                    war.isMyTurn = true
                    if (ConfigUtil.getBoolean(ConfigEnum.ONLY_ROBOT)) {
                        checkRivalRobot()
                        meBehavior.behaviors.clear()
                    }
                    // 异步执行出牌策略，以便监听出牌后的卡牌变动
                    (OutCardThread {
                        (ConfigUtil.getBoolean(ConfigEnum.RANDOM_EMOTION) && war.me.turn == 0).isTrue {
                            GameUtil.sendGreetEmoji()
                            SystemUtil.delayShortMedium()
                        }
                        val start = System.currentTimeMillis()
                        DeckStrategyActuator.outCard()
                        if (ConfigUtil.getBoolean(ConfigEnum.RANDOM_EMOTION) && System.currentTimeMillis() - start > 60_000) {
                            GameUtil.sendErrorEmoji()
                        }
                        if (ConfigUtil.getBoolean(ConfigEnum.ONLY_ROBOT)) {
                            checkMeRobot()
                        }
                    }.also { addTask(it) }).start()
                } else {
                    log.info { "对方回合" }
                    war.isMyTurn = false
                    cancelAllTask()
                    if (ConfigUtil.getBoolean(ConfigEnum.ONLY_ROBOT)) {
                        rivalBehavior.behaviors.clear()
                    }
                    ConfigUtil.getBoolean(ConfigEnum.RANDOM_EMOTION).isTrue {
                        DeckStrategyActuator.randEmoji()
                    }
                    if (ConfigUtil.getBoolean(ConfigEnum.RANDOM_EVENT)) {
                        (DeckStrategyThread({
                            DeckStrategyActuator.randomDoSomething()
                        }, "Random Do Something Thread").also { addTask(it) }).start()
                    }
                }
            } else if (tagChangeEntity.value == StepEnum.MAIN_END.name) {
                war.isMyTurn = false
                cancelAllTask()
            }
        }
        return false
    }


    private val rivalBehavior by lazy {
        PlayerBehavior(war.rival)
    }

    private val meBehavior by lazy {
        PlayerBehavior(war.me)
    }

    override fun dealBlockIsOver(line: String, block: Block): Boolean {
        if (ConfigUtil.getBoolean(ConfigEnum.ONLY_ROBOT)) {
            if (block.blockType === BlockTypeEnum.ATTACK || block.blockType === BlockTypeEnum.PLAY) {
                val behavior = Behavior(block.blockType)
                if (war.currentPlayer == war.me) {
                    meBehavior.behaviors.add(behavior)
                } else if (war.currentPlayer == war.rival) {
                    rivalBehavior.behaviors.add(behavior)
                }
            }
        }
        return false
    }

    private fun formatFieldInformation(war: War): String {
        val sb = StringBuilder()

        fun formatPlayerField(player: Player, title: String) {
            sb.appendLine(title)

            // Hero
            player.playArea.hero?.let { hero ->
                sb.appendLine("Hero: ${hero.entityName} (Atk: ${hero.atc}, Health: ${hero.blood()})")
            } ?: sb.appendLine("Hero: (None)")

            // Mana
            sb.appendLine("Mana: ${player.usableResource}/${player.resources}")

            // Minions
            sb.appendLine("Minions:")
            if (player.playArea.cards.isEmpty()) {
                sb.appendLine("- (No minions)")
            } else {
                player.playArea.cards.forEach { minion ->
                    val keywords = mutableListOf<String>()
                    if (minion.isTaunt) keywords.add("Taunt")
                    if (minion.isDivineShield) keywords.add("Divine Shield")
                    if (minion.isStealth) keywords.add("Stealth")
                    if (minion.isPoisonous) keywords.add("Poisonous")
                    if (minion.isRush) keywords.add("Rush")
                    if (minion.isWindfury) keywords.add("Windfury")
                    if (minion.isLifesteal) keywords.add("Lifesteal")
                    if (minion.isReborn) keywords.add("Reborn")
                    if (minion.isElusive) keywords.add("Elusive")
                    if (minion.spellPower > 0) keywords.add("SpellPower:${minion.spellPower}")

                    val keywordsString = if (keywords.isNotEmpty()) ", Keywords: [${keywords.joinToString(", ")}]" else ""
                    sb.appendLine("- ${minion.entityName} (Atk: ${minion.atc}, Health: ${minion.blood()}$keywordsString)")
                }
            }

            // Weapon
            player.playArea.weapon?.let { weapon ->
                sb.appendLine("Weapon: ${weapon.entityName} (Atk: ${weapon.atc}, Dur: ${weapon.blood()})")
            } ?: sb.appendLine("Weapon: (None)")

            // Secrets
            sb.appendLine("Secrets: ${player.secretArea.cards.size}")

            // Hand Size
            sb.appendLine("Hand Size: ${player.handArea.cards.size}")

            // Deck Size
            sb.appendLine("Deck Size: ${player.deckArea.cards.size}")
        }

        formatPlayerField(war.me, "--- My Field ---")
        formatPlayerField(war.rival, "--- Rival's Field ---")

        return sb.toString()
    }
}
