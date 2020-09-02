/*
 * MurderMystery - Find the murderer, kill him and survive!
 * Copyright (C) 2020  Plugily Projects - maintained by Tigerpanzer_02, 2Wild4You and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package plugily.projects.murdermystery;

import com.gmail.filoghost.holographicdisplays.api.Hologram;
import com.gmail.filoghost.holographicdisplays.api.HologramsAPI;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import pl.plajerlair.commonsbox.database.MysqlDatabase;
import pl.plajerlair.commonsbox.minecraft.configuration.ConfigUtils;
import pl.plajerlair.commonsbox.minecraft.serialization.InventorySerializer;
import plugily.projects.murdermystery.api.StatsStorage;
import plugily.projects.murdermystery.arena.Arena;
import plugily.projects.murdermystery.arena.ArenaEvents;
import plugily.projects.murdermystery.arena.ArenaRegistry;
import plugily.projects.murdermystery.arena.ArenaUtils;
import plugily.projects.murdermystery.arena.special.SpecialBlockEvents;
import plugily.projects.murdermystery.arena.special.mysterypotion.MysteryPotionRegistry;
import plugily.projects.murdermystery.arena.special.pray.PrayerRegistry;
import plugily.projects.murdermystery.commands.arguments.ArgumentsRegistry;
import plugily.projects.murdermystery.events.*;
import plugily.projects.murdermystery.events.spectator.SpectatorEvents;
import plugily.projects.murdermystery.events.spectator.SpectatorItemEvents;
import plugily.projects.murdermystery.handlers.*;
import plugily.projects.murdermystery.handlers.items.SpecialItem;
import plugily.projects.murdermystery.handlers.language.LanguageManager;
import plugily.projects.murdermystery.handlers.party.PartyHandler;
import plugily.projects.murdermystery.handlers.party.PartySupportInitializer;
import plugily.projects.murdermystery.handlers.rewards.RewardsFactory;
import plugily.projects.murdermystery.handlers.sign.SignManager;
import plugily.projects.murdermystery.user.User;
import plugily.projects.murdermystery.user.UserManager;
import plugily.projects.murdermystery.user.data.MysqlManager;
import plugily.projects.murdermystery.utils.*;
import plugily.projects.murdermystery.utils.services.ServiceRegistry;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;

/**
 * @author Plajer
 * <p>
 * Created at 03.08.2018
 */
public class Main extends JavaPlugin {

  private ExceptionLogHandler exceptionLogHandler;
  private boolean forceDisable = false;
  private BungeeManager bungeeManager;
  private RewardsFactory rewardsHandler;
  private MysqlDatabase database;
  private SignManager signManager;
  private CorpseHandler corpseHandler;
  private PartyHandler partyHandler;
  private ConfigPreferences configPreferences;
  private ArgumentsRegistry argumentsRegistry;
  private HookManager hookManager;
  private UserManager userManager;
  private ChatManager chatManager;

  @Override
  public void onEnable() {
    if (!validateIfPluginShouldStart()) {
      return;
    }

    long start = System.currentTimeMillis();

    ServiceRegistry.registerService(this);
    exceptionLogHandler = new ExceptionLogHandler(this);
    LanguageManager.init(this);
    saveDefaultConfig();

    Debugger.setEnabled(getDescription().getVersion().contains("b") || getConfig().getBoolean("Debug", false));

    Debugger.debug("[System] Initialization start");
    if (getConfig().getBoolean("Developer-Mode", false)) {
      Debugger.deepDebug(true);
      Debugger.debug(Level.FINE, "Deep debug enabled");
      for (String listenable : new ArrayList<>(getConfig().getStringList("Performance-Listenable"))) {
        Debugger.monitorPerformance(listenable);
      }
    }

    configPreferences = new ConfigPreferences(this);
    setupFiles();
    initializeClasses();
    checkUpdate();
    Debugger.debug("[System] Initialization finished took {0}ms", System.currentTimeMillis() - start);

    Debugger.debug("Plugin loaded! Hooking into soft-dependencies in a while!");
    //start hook manager later in order to allow soft-dependencies to fully load
    Bukkit.getScheduler().runTaskLater(this, () -> hookManager = new HookManager(), 20L * 5);
    if (configPreferences.getOption(ConfigPreferences.Option.NAMETAGS_HIDDEN)) {
      Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, () ->
        Bukkit.getOnlinePlayers().forEach(ArenaUtils::updateNameTagsVisibility), 60, 140);
    }
  }

  private boolean validateIfPluginShouldStart() {
    if (ServerVersion.Version.isCurrentLower(ServerVersion.Version.v1_12_R1)) {
      MessageUtils.thisVersionIsNotSupported();
      Debugger.sendConsoleMsg("&cYour server version is not supported by Murder Mystery!");
      Debugger.sendConsoleMsg("&cSadly, we must shut off. Maybe you consider changing your server version?");
      forceDisable = true;
      getServer().getPluginManager().disablePlugin(this);
      return false;
    }
    try {
      Class.forName("org.spigotmc.SpigotConfig");
    } catch (Exception e) {
      MessageUtils.thisVersionIsNotSupported();
      Debugger.sendConsoleMsg("&cYour server software is not supported by Murder Mystery!");
      Debugger.sendConsoleMsg("&cWe support only Spigot and Spigot forks only! Shutting off...");
      forceDisable = true;
      getServer().getPluginManager().disablePlugin(this);
      return false;
    }
    return true;
  }

  @Override
  public void onDisable() {
    if (forceDisable) {
      return;
    }
    Debugger.debug("System disable initialized");
    long start = System.currentTimeMillis();

    Bukkit.getLogger().removeHandler(exceptionLogHandler);
    saveAllUserStatistics();
    if (hookManager != null && hookManager.isFeatureEnabled(HookManager.HookFeature.CORPSES)) {
      HologramsAPI.getHolograms(this).forEach(Hologram::delete);
    }
    if (configPreferences.getOption(ConfigPreferences.Option.DATABASE_ENABLED)) {
      getMysqlDatabase().shutdownConnPool();
    }

    for (Arena arena : ArenaRegistry.getArenas()) {
      arena.getScoreboardManager().stopAllScoreboards(true);
      for (Player player : arena.getPlayers()) {
        arena.doBarAction(Arena.BarAction.REMOVE, player);
        arena.teleportToEndLocation(player);
        player.setFlySpeed(0.1f);
        if (configPreferences.getOption(ConfigPreferences.Option.INVENTORY_MANAGER_ENABLED)) {
          InventorySerializer.loadInventory(this, player);
        } else {
          player.getInventory().clear();
          player.getInventory().setArmorContents(null);
          player.getActivePotionEffects().forEach(pe -> player.removePotionEffect(pe.getType()));
          player.setWalkSpeed(0.2f);
        }
      }
      arena.teleportAllToEndLocation();
      arena.cleanUpArena();
    }
    Debugger.debug("System disable finished took {0}ms", System.currentTimeMillis() - start);
  }

  private void initializeClasses() {
    chatManager = new ChatManager(this);
    if (getConfig().getBoolean("BungeeActivated", false)) {
      bungeeManager = new BungeeManager(this);
    }
    if (configPreferences.getOption(ConfigPreferences.Option.DATABASE_ENABLED)) {
      FileConfiguration config = ConfigUtils.getConfig(this, "mysql");
      database = new MysqlDatabase(config.getString("user"), config.getString("password"), config.getString("address"));
    }
    argumentsRegistry = new ArgumentsRegistry(this);
    userManager = new UserManager(this);
    Utils.init(this);
    SpecialItem.loadAll();
    PermissionsManager.init();
    new ArenaEvents(this);
    new SpectatorEvents(this);
    new QuitEvent(this);
    new JoinEvent(this);
    new ChatEvents(this);
    registerSoftDependenciesAndServices();
    User.cooldownHandlerTask();
    ArenaRegistry.registerArenas();
    new Events(this);
    new LobbyEvent(this);
    new SpectatorItemEvents(this);
    rewardsHandler = new RewardsFactory(this);
    signManager = new SignManager(this);
    corpseHandler = new CorpseHandler(this);
    partyHandler = new PartySupportInitializer().initialize(this);
    new BowTrailsHandler(this);
    MysteryPotionRegistry.init(this);
    PrayerRegistry.init(this);
    new SpecialBlockEvents(this);
  }

  private void registerSoftDependenciesAndServices() {
    Debugger.debug("Hooking into soft dependencies");
    long start = System.currentTimeMillis();

    startPluginMetrics();
    if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      Debugger.debug("Hooking into PlaceholderAPI");
      new PlaceholderManager().register();
    }
    Debugger.debug("Hooked into soft dependencies took {0}ms", System.currentTimeMillis() - start);
  }

  private void startPluginMetrics() {
    Metrics metrics = new Metrics(this);
    if (!metrics.isEnabled())
      return;

    metrics.addCustomChart(new Metrics.SimplePie("database_enabled", () -> String.valueOf(configPreferences.getOption(ConfigPreferences.Option.DATABASE_ENABLED))));
    metrics.addCustomChart(new Metrics.SimplePie("bungeecord_hooked", () -> String.valueOf(configPreferences.getOption(ConfigPreferences.Option.BUNGEE_ENABLED))));
    metrics.addCustomChart(new Metrics.SimplePie("locale_used", () -> LanguageManager.getPluginLocale().getPrefix()));
    metrics.addCustomChart(new Metrics.SimplePie("update_notifier", () -> {
      if (getConfig().getBoolean("Update-Notifier.Enabled", true)) {
        return getConfig().getBoolean("Update-Notifier.Notify-Beta-Versions", true) ? "Enabled with beta notifier" : "Enabled";
      }
      return getConfig().getBoolean("Update-Notifier.Notify-Beta-Versions", true) ? "Beta notifier only" : "Disabled";
    }));
  }

  private void checkUpdate() {
    if (!getConfig().getBoolean("Update-Notifier.Enabled", true)) {
      return;
    }
    UpdateChecker.init(this, 66614).requestUpdateCheck().whenComplete((result, exception) -> {
      if (!result.requiresUpdate()) {
        return;
      }
      if (result.getNewestVersion().contains("b")) {
        if (getConfig().getBoolean("Update-Notifier.Notify-Beta-Versions", true)) {
          Debugger.sendConsoleMsg("&c[MurderMystery] Your software is ready for update! However it's a BETA VERSION. Proceed with caution.");
          Debugger.sendConsoleMsg("&c[MurderMystery] Current version %old%, latest version %new%".replace("%old%", getDescription().getVersion()).replace("%new%",
            result.getNewestVersion()));
        }
        return;
      }
      MessageUtils.updateIsHere();
      Debugger.sendConsoleMsg("&aYour MurderMystery plugin is outdated! Download it to keep with latest changes and fixes.");
      Debugger.sendConsoleMsg("&aDisable this option in config.yml if you wish.");
      Debugger.sendConsoleMsg("&eCurrent version: &c" + getDescription().getVersion() + "&e Latest version: &a" + result.getNewestVersion());
    });
  }

  private void setupFiles() {
    for (String fileName : Arrays.asList("arenas", "bungee", "rewards", "stats", "lobbyitems", "mysql", "specialblocks")) {
      File file = new File(getDataFolder() + File.separator + fileName + ".yml");
      if (!file.exists()) {
        saveResource(fileName + ".yml", false);
      }
    }
  }

  public RewardsFactory getRewardsHandler() {
    return rewardsHandler;
  }

  public BungeeManager getBungeeManager() {
    return bungeeManager;
  }

  public PartyHandler getPartyHandler() {
    return partyHandler;
  }

  public ChatManager getChatManager() {
    return chatManager;
  }

  public ConfigPreferences getConfigPreferences() {
    return configPreferences;
  }

  public MysqlDatabase getMysqlDatabase() {
    return database;
  }

  public SignManager getSignManager() {
    return signManager;
  }

  public CorpseHandler getCorpseHandler() {
    return corpseHandler;
  }

  public ArgumentsRegistry getArgumentsRegistry() {
    return argumentsRegistry;
  }

  public HookManager getHookManager() {
    return hookManager;
  }

  public UserManager getUserManager() {
    return userManager;
  }

  private void saveAllUserStatistics() {
    for (Player player : getServer().getOnlinePlayers()) {
      User user = userManager.getUser(player);
      if (userManager.getDatabase() instanceof MysqlManager) {
        StringBuilder update = new StringBuilder(" SET ");
        for (StatsStorage.StatisticType stat : StatsStorage.StatisticType.values()) {
          if (!stat.isPersistent()) continue;
          if (update.toString().equalsIgnoreCase(" SET ")) {
            update.append(stat.getName()).append("=").append(user.getStat(stat));
          }
          update.append(", ").append(stat.getName()).append("=").append(user.getStat(stat));
        }
        String finalUpdate = update.toString();
        //copy of userManager#saveStatistic but without async database call that's not allowed in onDisable method.
        ((MysqlManager) userManager.getDatabase()).getDatabase().executeUpdate("UPDATE " + ((MysqlManager) getUserManager().getDatabase()).getTableName()
          + finalUpdate + " WHERE UUID='" + user.getPlayer().getUniqueId().toString() + "';");
        continue;
      }
      for (StatsStorage.StatisticType stat : StatsStorage.StatisticType.values()) {
        userManager.getDatabase().saveStatistic(user, stat);
      }
    }
  }


}
