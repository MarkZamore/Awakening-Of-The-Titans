# FTB Quests / FTB Teams progress ownership audit

Read-only inspection of the pinned author files:

| Project | File | SHA256 |
| --- | --- | --- |
| FTB Quests | `ftb-quests-fabric-2101.1.36.jar` | `68df590b681255e11c52d1850d0dd02d3ad9a3c5d82fdb9e37312111a60ddefb` |
| FTB Teams | `ftb-teams-fabric-2101.1.11.jar` | `39e34b438e0232a614a8cf1269fcff2705b85fde8b930a03d558bc66a03c7cf6` |

The evidence is the actual class tables/bytecode and author quest SNBT. No mod, config, team, quest definition or live save was changed by this audit.

## Ownership and persistence

Quest task progress and completion belong to `TeamData.teamId`, rather than to the player's current nickname. The data also contains per-player UUID records and claimed rewards. `default_reward_team: false` is a reward setting; it does not make all task progress individual. The configured `default_autoclaim_rewards: "disabled"` leaves rewards available for explicit claiming.

FTB Teams indexes known player teams by `ServerPlayer.getUUID()`. On login it reuses that UUID's `PlayerTeam`; if the nickname differs, it updates `playerName` and marks the existing data dirty. An ordinary personal team has its own ID equal to the portable player UUID. An effective party team can have a different ID, and quest progress then belongs to that effective team. Check both personal identity and effective membership rather than assuming every `getTeamId()` must equal the player UUID.

The stopped `integrated-02` world contains native `ftbteams/player/fb63cfc0-aed5-596c-91e0-ebe7e8052c92.snbt` with that UUID as owner, and `ftbquests/fb63cfc0-aed5-596c-91e0-ebe7e8052c92.snbt`. The latter records Paradis task `2B2EF758632E91CC` and quest `1976F64D7305994E` complete. This is an observation of actual ownership/storage, **not a sufficient persistence sentinel**: that dimension task could simply complete again after progress loss.

`TeamData.saveIfChanged()` resolves the server's `ftbquests` save path and writes `<team UUID>.snbt` using the native SNBT writer. FTB Teams resolves the server's `ftbteams` save path, including `ftbteams.snbt` and per-type team records. The manager's own ID in `ftbteams.snbt` is separate from player/team UUIDs. Preserve the whole directories, including party records and per-player data.

The candidate launcher's `WorldTransferService.CopyWorldDirectoryAsync` copies every ordinary world file except `session.lock`; its archive/hash path includes all files. It does not whitelist only vanilla playerdata and does not exclude `ftbquests` or `ftbteams`. Therefore these native records belong to the transferable world. That source inspection is not a substitute for a stopped-world copy/reopen test with both players.

## Native query APIs

Use these on the real server thread and query actual player UUIDs:

```java
// Public entry point, public static INSTANCE field.
dev.ftb.mods.ftbquests.quest.ServerQuestFile file =
    dev.ftb.mods.ftbquests.quest.ServerQuestFile.INSTANCE;

// Public methods inherited from BaseQuestFile:
Task getTask(long taskId);
Optional<TeamData> getTeamData(net.minecraft.class_1657 player);
TeamData getNullableTeamData(java.util.UUID effectiveTeamId);

// Public methods on dev.ftb.mods.ftbquests.quest.TeamData:
static TeamData get(net.minecraft.class_1657 player);
java.util.UUID getTeamId();
long getProgress(long taskId);
long getProgress(dev.ftb.mods.ftbquests.quest.task.Task task);
boolean isCompleted(dev.ftb.mods.ftbquests.quest.QuestObject object);
void saveIfChanged();

// Team API:
dev.ftb.mods.ftbteams.api.FTBTeamsAPI.api().getManager();
Optional<Team> manager.getPlayerTeamForPlayerID(java.util.UUID playerId);
Optional<Team> manager.getTeamForPlayerID(java.util.UUID playerId);
// Public methods on dev.ftb.mods.ftbteams.api.Team:
java.util.UUID getId();
java.util.UUID getTeamId();
java.util.UUID getOwner();
java.util.Set<java.util.UUID> getMembers();
```

For reflection, the task and quest-object parameter classes are `dev.ftb.mods.ftbquests.quest.task.Task` and `dev.ftb.mods.ftbquests.quest.QuestObject`. The player's mapped base class is `net.minecraft.class_1657`. Query the personal team's `getId()` to compare with the portable UUID; `PlayerTeam.getTeamId()` follows the effective team. Query the effective team's ID/members and require `TeamData.getTeamId()` to match it. Use `getNullableTeamData` for an offline query so a missing record is not silently created.

## Disposable native progress sentinel

The author has **no CheckmarkTask** in the supplied chapter. Do not add a quest merely to test persistence. Existing task `79EEEC6BA7256200` requires one `immersive_aircraft:biplane`, belongs to quest `634ACBE9F25041A6`, and has no quest dependency. It is suitable for a manually completed sentinel if both QA inventories lack that item before seeding and verification.

Use the real registered command, dispatched by the server with the actual connected player's name:

```text
ftbquests change_progress <actualPlayerName> complete 79EEEC6BA7256200
```

The command resolves that player's native team data, constructs a native `ProgressChange` with reset disabled, and calls the existing task's progress lifecycle. It does not invoke a reward-claim command or consume a biplane. Do not claim the coal-block reward as part of this probe. Query the existing task through `getTask(Long.parseUnsignedLong("79EEEC6BA7256200", 16))`, then record task progress/completion, personal UUID/team ID, effective team ID/members, and quest data ownership. Native progress should equal the task's maximum, normally `1L`; require the actual `getMaxProgress()` rather than relying only on this number.

Save through the native lifecycle, rejoin both registered players, normally stop, copy the complete fixture world, and reopen with host roles exchanged. Requery the same IDs before running the completion command again. Require no biplane in either inventory so missing quest data cannot be hidden by automatic item detection. Preserve separate team records; if players intentionally belong to one party, shared progress is expected author behavior.

This audit supplies the exact operation/API for the paired runtime probe. It does not mark that probe, party creation/join behavior, nickname changes, genuine Steam transport, or every other mod's persistence as tested. Future author changes to quest IDs can require a separate data migration even when player UUIDs remain stable.
