# Проверки идентичности, скинов и прогресса

Это инструменты для отдельного стенда `tools/runtime-qa`, а не моды сборки. Они не попадают в `mods/`, активный lock или payload и не изменяют личный игровой экземпляр. Путь стенда обязан содержать маркер `.aot-runtime-qa.json`; результаты и PNG остаются в его частной папке `evidence/`.

## Сборка

Из корня исходников сборки, с JDK 21:

```powershell
python tools/identity-compat/build_probe.py --jdk $env:JAVA_HOME --out ../build/identity-compat
dotnet build tools/identity-compat/SkinPeerLab.csproj -p:LauncherAssemblyDirectory=$candidateLauncherDirectory
```

`build_probe.py` собирает только Java-классы `Native*Probe` с `--release 21`. JAR имеет фиксированные ZIP-время и порядок записей; команда печатает SHA256. Каждую версию, которую уже загрузил работающий JVM, собирать в новую выходную папку. Не заменять JAR под работающим клиентом.

Хост QA подключает JAR отдельным загрузчиком и вызывает `tick(Object client, Object server, Object player, String report)`. В боевом запуске эти свойства и инструменты отсутствуют. Классы привязаны к фактическим intermediary-символам Minecraft 1.21.1 и Danny’s AOT 2.5.2; перенос на другую версию требует повторной проверки.

## Скины

`NativeIdentityProbe` проверяет UUID фактического клиента, native `PlayerSkin`, модель и загруженные на GPU пиксели по выбранному PNG. Проверяет только непрозрачные RGB-пиксели исходного изображения: ванильная обработка прозрачности не считается ошибкой скина. GL texture binding восстанавливается после чтения.

Контрольный неизвестный профиль не должен получать скин локального игрока. Конструкторы настоящего `GameProfile` отдельно проверяют сопоставление offline-name и e4steam tunnel UUID с переносимым UUID; это проверка адаптера, а не успешное соединение Steam.

При двух клиентах probe ищет настоящий удалённый `AbstractClientPlayer` в том же мире. Для него нужны отдельные UUID и разные эталонные PNG. Проверка ждёт native texture URL, модели и GPU-пикселей реального пира; до его появления результат обозначается `PASS_SELF_AWAITING_ACTUAL_PEER`. Созданный для отрицательного контроля объект `PlayerInfo` не выдаётся за удалённого игрока.

`SkinPeerLab.cs` подаёт объявления пира и PNG через действующий `SkinService`: `ObservePeer`, запрос по производственному протоколу, `HandleIncomingAsync`, registry и HTTP endpoint. Транспорт лаборатории — локальный TCP с одной разрешённой парой и одним протоколом. Он не входит в Steam и не подтверждает реальное обнаружение друзей или Steam P2P. Хост должен также передать обоих игроков в настоящий `PortableIdentityRegistryService` через `Prepare(local)` и `ObservePeer(peer)`.

## Прогресс

`NativeProgressProbe` запускается только при одном из явных файлов `progress-seed.flag` / `progress-verify.flag` в текущей папке evidence. Он проверяет единственного фактического игрока встроенного сервера и совпадение UUID с параметром запуска.

Seed меняет только одноразовый QA-мир: native командами выдаёт 7 алмазов, уровень 13, кровь Eldian и силу Attack Titan. Инвентарь предварительно очищается. Игроки выбираются через `@a` после проверки единственного игрока: UUID-литералы штатный players-only аргумент игры отвергает. Используется настоящий Brigadier dispatcher с исключениями разбора и числовыми результатами. Нативный снимок состояния записывается до проверки условий, чтобы не скрывать ошибки подготовки.

После обычного сохранения и штатного закрытия клиента второй запуск должен использовать тот же переносимый UUID и другой ник. Verify читает native инвентарь, XP и авторские `TitanPowerData` / `BloodlineData`, сравнивая их с seed. Он не утверждает исчерпывающую проверку всех модов, другого хоста, гостя Steam или автоматического переноса мира.

`NativePairedProgressProbe` дополняет эту проверку настоящими двумя `ServerPlayer` встроенного сервера. Он ждёт реального гостя и сверяет оба UUID и имени с production identity registry. Флаги `paired-progress-seed.flag` / `paired-progress-verify.flag` разрешают единственный проход. Seed очищает оба инвентаря и выдаёт разные ненулевые показатели: первому хосту 7 алмазов, XP 13 и Attack Titan; гостю 11 алмазов, XP 17 и Armored Titan; обоим кровь Eldian. Команды используют фактические проверенные имена игроков.

Перед повторным запуском штатно сохранить и закрыть оба клиента. Оба игрока меняют ники, сохраняя UUID. Verify сравнивает native инвентарь, XP и обе силы с `identity-paired-progress-expected.json` в корне стенда. Для смены хоста дополнительно используется `paired-progress-swap.flag`: локальным хостом обязан стать прежний гость. Ожидаемый JSON переносится без изменений вместе с отдельной копией остановленного QA-мира; абсолютный путь стенда не является идентификатором мира.

QA-хост обязан вызывать настоящую подготовку профиля лаунчера: `WorldPlayerProfileService.PrepareWorldsForLaunch` перед запуском; для смены владельца — `PrepareWorldForOutgoingTransfer` на staging-копии и `PrepareReceivedWorldForIdentity` в приёмном стенде. Эти production операции экспортируют прежний `level.dat Player` и выбирают профиль нового хоста по UUID. Probe не заменяет их и сам не редактирует NBT. Копирование разрешено только после подтверждённой штатной остановки; этот синтетический fixture не является резервной копией личного мира.

`NativeQuestsProgressProbe` отдельно проверяет существующий авторский biplane task FTB Quests `79EEEC6BA7256200`. Сначала требует двух зарегистрированных игроков, их разные personal/effective team UUID, task progress 0 и отсутствие biplane в обоих инвентарях. Затем использует штатную FTB command от настоящего QA-хоста с native editor permission, без claim rewards. Console source не подходит: FTB явно требует executed-by-player; фактическое дерево Brigadier и оба результата `canUse` записываются отдельно. Права команд не изменяются ради проверки.

Флаги `quests-seed.flag` / `quests-verify.flag` и дополнительный `quests-swap.flag` управляют фазами. Отдельный `identity-quests-progress-expected.json` переносится с остановленным миром. Verify читает тот же native task и максимальное значение, team ID/membership и completion до повторного выполнения команд; требует новые ники и прежние UUID. Отсутствие biplane исключает скрытое повторное автоматическое завершение после потери данных. См. [ftb-progress-audit.md](ftb-progress-audit.md). Новые квесты в авторский контент не добавляются.

## Подготовка остановленной копии для смены хоста

`WorldTransferLab.csproj` — отдельный CLI, ссылающийся на полный собранный лаунчер. Он не собирает и не меняет исходники лаунчера. Собрать его в отдельный artifact output с `LauncherAssemblyDirectory` и отдельным `BaseIntermediateOutputPath`.

```text
dotnet WorldTransferLab.dll <sourceRoot> <sourceWorldAbs> <sourceExitJsonAbs> <destinationRoot> <destinationWorldAbs> <destinationExitJsonAbs> <oldHostName> <oldSteamID> <newHostName> <newSteamID>
```

Оба корня должны быть непересекающимися marked QA fixtures внутри artifacts. Требуются реальные exitCode 0, соответствующие им `run.json`, отсутствие живых записанных QA-процессов и закрытый native `session.lock`. Целевой мир и receipts должны отсутствовать. Инструмент ничего не удаляет и не перезаписывает.

Полный мир сначала копируется в новый destination staging без `session.lock`. Затем действующие `WorldPlayerProfileService.PrepareWorldForOutgoingTransfer` и `PrepareReceivedWorldForIdentity` экспортируют профиль прежнего владельца и выбирают профиль нового. Только подготовленная копия перемещается в целевую папку. SHA исходного мира проверяются до и после; native `ftbquests`, `ftbteams` и `data` сохраняются byte-identical. Ожидаемые paired/quests JSON копируются без изменений. Сбой сохраняет staging и отчёт для проверки, не меняя исходный мир.

Успешный результат называется `PREPARED_AWAITING_NATIVE_HOST_ROLE_SWAP`: он подтверждает подготовку копии, а не успешный новый клиент или прогресс. Новый хост после обычного native входа и подключения второго игрока выполняет оба verify probe с флагом swap.

## Карта и Steam

См. [xaero-report.md](xaero-report.md), отдельный native probe и production sync fixture в [xaero/README.md](xaero/README.md). Проверка соединения двух клиентов по локальному TCP подтверждает игровой обмен и identity/skin пути, но не является PASS настоящего Steam. Для Steam отдельно нужны нативное однократное открытие мира из pause menu без промежуточного экрана, запись мира в multiplayer через e4steam, подключение и повторный вход с двумя настоящими аккаунтами. Результаты выполненных и оставшихся проверок хранятся в основном `TESTING-STATUS.md` сборки.
