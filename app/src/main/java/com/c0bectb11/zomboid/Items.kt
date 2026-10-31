package com.c0bectb11.zomboid

enum class ItemKind { FOOD, DRINK, MED, MELEE, GUN, AMMO, MATERIAL }

class ItemDef(
    val id: String,
    val name: String,
    val kind: ItemKind,
    val weight: Float,
    val color: Int,
    val hunger: Float = 0f,
    val thirst: Float = 0f,
    val heal: Float = 0f,
    val stopsBleeding: Boolean = false,
    val dmg: Float = 0f,
    val range: Float = 0f,
    val cooldown: Float = 0f,
    val noise: Float = 0f,
)

class Stack(val id: String, var n: Int) {
    val def: ItemDef get() = Items.get(id)
}

object Items {
    val FISTS = ItemDef("fists", "Кулаки", ItemKind.MELEE, 0f, 0xFFE0B090.toInt(), dmg = 8f, range = 0.9f, cooldown = 0.55f, noise = 2f)

    private val list = listOf(
        ItemDef("canned", "Консервы", ItemKind.FOOD, 0.6f, 0xFFB0B8C0.toInt(), hunger = 35f),
        ItemDef("chips", "Чипсы", ItemKind.FOOD, 0.2f, 0xFFE8C040.toInt(), hunger = 15f, thirst = -5f),
        ItemDef("bread", "Хлеб", ItemKind.FOOD, 0.3f, 0xFFC89048.toInt(), hunger = 25f),
        ItemDef("apple", "Яблоко", ItemKind.FOOD, 0.2f, 0xFFD03030.toInt(), hunger = 10f, thirst = 5f),
        ItemDef("water", "Бутылка воды", ItemKind.DRINK, 0.6f, 0xFF60A8E8.toInt(), thirst = 45f),
        ItemDef("soda", "Газировка", ItemKind.DRINK, 0.4f, 0xFFE04848.toInt(), thirst = 25f, hunger = 5f),
        ItemDef("bandage", "Бинт", ItemKind.MED, 0.1f, 0xFFF0F0F0.toInt(), heal = 8f, stopsBleeding = true),
        ItemDef("rag", "Тряпка", ItemKind.MED, 0.1f, 0xFFB8A888.toInt()),
        ItemDef("disinfectant", "Дезинфицирующее", ItemKind.MED, 0.3f, 0xFF40B060.toInt()),
        ItemDef("tweezers", "Пинцет", ItemKind.MED, 0.05f, 0xFFB0B0B8.toInt()),
        ItemDef("pills", "Обезболивающее", ItemKind.MED, 0.1f, 0xFF80E080.toInt(), heal = 15f),
        ItemDef("knife", "Кухонный нож", ItemKind.MELEE, 0.4f, 0xFFC0C0C8.toInt(), dmg = 22f, range = 1.0f, cooldown = 0.4f, noise = 2f),
        ItemDef("bat", "Бейсбольная бита", ItemKind.MELEE, 1.5f, 0xFFB07840.toInt(), dmg = 38f, range = 1.3f, cooldown = 0.7f, noise = 4f),
        ItemDef("crowbar", "Лом", ItemKind.MELEE, 2.0f, 0xFF707078.toInt(), dmg = 48f, range = 1.3f, cooldown = 0.85f, noise = 5f),
        ItemDef("hammer", "Молоток", ItemKind.MELEE, 1.0f, 0xFF905030.toInt(), dmg = 20f, range = 1.0f, cooldown = 0.55f, noise = 3f),
        ItemDef("pistol", "Пистолет", ItemKind.GUN, 1.0f, 0xFF303034.toInt(), dmg = 70f, range = 10f, cooldown = 0.45f, noise = 24f),
        ItemDef("ammo", "Патроны 9мм", ItemKind.AMMO, 0.02f, 0xFFD0B040.toInt()),
        ItemDef("planks", "Доски", ItemKind.MATERIAL, 1.0f, 0xFFA07040.toInt()),
    )
    private val map = list.associateBy { it.id }

    fun get(id: String): ItemDef = map[id] ?: FISTS

    /** Таблицы лута: (id, вес шанса, мин, макс) */
    class Loot(val id: String, val w: Int, val min: Int = 1, val max: Int = 1)

    val lootFridge = listOf(Loot("water", 5, 1, 2), Loot("soda", 5, 1, 2), Loot("apple", 4, 1, 3), Loot("bread", 3), Loot("canned", 2))
    val lootCounter = listOf(Loot("canned", 5, 1, 2), Loot("chips", 4), Loot("knife", 3), Loot("bread", 2), Loot("water", 2), Loot("disinfectant", 1), Loot("rag", 2))
    val lootWardrobe = listOf(Loot("bat", 2), Loot("bandage", 2), Loot("rag", 5, 1, 3), Loot("planks", 2, 1, 2), Loot("hammer", 2), Loot("chips", 1))
    val lootShelf = listOf(Loot("chips", 4), Loot("canned", 3), Loot("hammer", 2), Loot("planks", 3, 1, 3), Loot("crowbar", 1), Loot("pills", 2))
    val lootMed = listOf(Loot("bandage", 6, 1, 3), Loot("disinfectant", 4), Loot("tweezers", 3), Loot("pills", 3, 1, 2))
    val lootStore = listOf(Loot("canned", 6, 1, 3), Loot("chips", 6, 1, 3), Loot("water", 6, 1, 3), Loot("soda", 5, 1, 3), Loot("bread", 3, 1, 2))
    val lootPolice = listOf(Loot("pistol", 3), Loot("ammo", 6, 6, 15), Loot("bandage", 3, 1, 2), Loot("crowbar", 2))
}
