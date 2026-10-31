# Мёртвый город

Выживание среди зомби в духе Project Zomboid для телефонов. Движок — **Godot 4.4**, один проект собирается под Android и iOS.

## Скачать
- **Android:** Releases → «Последняя сборка» → `zomboid.apk`
- **iOS:** там же `zomboid-ios-xcode.zip` — Xcode-проект. Для установки на iPhone нужен аккаунт Apple Developer (TestFlight).

Сборки делаются автоматически в GitHub Actions после каждого изменения.

## Что есть
- 3D-город: дома с комнатами и мебелью, магазины, полиция, машины, фонари, деревья
- Свет и тени, смена дня и ночи, фонарик, туман видимости, снятие крыш и прорезание стен
- Двери, окна, перелезание через разбитые окна, баррикады
- Обыск мебели и багажников, инвентарь с весом
- Ранения по 16 частям тела: царапины, рваные раны, укусы, стекло; бинты, тряпки, дезинфекция, пинцет
- Голод, жажда, выносливость, заражение, красться, сбивание с ног
- Игра на двоих по Wi‑Fi (ENet), сохранения

## Структура
- `scripts/game.gd`, `world.gd`, `body.gd`, `items.gd` — логика игры
- `scripts/world_view.gd`, `world_mesh.gd`, `character.gd`, `mesher.gd`, `shaders/world.gdshader` — 3D
- `scripts/hud.gd`, `icons.gd`, `main.gd` — интерфейс и меню
- `scripts/net.gd`, `game_screen.gd` — сеть и игровой цикл
- Старая Android-версия на Kotlin — ветка `legacy-kotlin`

## Скриншоты для проверки
`godot --path . -- --demo=zombies --shot=out.png` (демо: day, zombies, inside, night, evening, climb, body, inv, loot, menu)
