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

package plugily.projects.murdermystery.arena.managers;

import it.feargames.fearboard.api.Sidebar;
import it.feargames.fearboard.api.SidebarManager;
import it.feargames.fearboard.api.animation.AnimatedLine;
import it.feargames.fearboard.api.animation.AnimatedLineFrame;
import me.clip.placeholderapi.PlaceholderAPI;
import org.apache.commons.lang.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import pl.plajerlair.commonsbox.string.StringFormatUtils;
import plugily.projects.murdermystery.Main;
import plugily.projects.murdermystery.api.StatsStorage;
import plugily.projects.murdermystery.arena.Arena;
import plugily.projects.murdermystery.arena.ArenaState;
import plugily.projects.murdermystery.arena.role.Role;
import plugily.projects.murdermystery.handlers.ChatManager;
import plugily.projects.murdermystery.handlers.language.LanguageManager;
import plugily.projects.murdermystery.user.User;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/**
 * @author Plajer
 * <p>
 * Created at 24.03.2019
 */
public class ScoreboardManager {

  private static final Main plugin = JavaPlugin.getPlugin(Main.class);
  private static final ChatManager chatManager = plugin.getChatManager();
  private static final String boardTitle = chatManager.colorMessage("Scoreboard.Title");
  private static SidebarManager sidebarManager;

  private final Map<User, Sidebar> sidebars = new WeakHashMap<>();
  private final Arena arena;

  public ScoreboardManager(Arena arena) {
    this.arena = arena;

    if (sidebarManager == null) {
      sidebarManager = new SidebarManager(plugin, null);

      Bukkit.getScheduler().runTaskTimer(plugin, () ->
        sidebars.forEach(ScoreboardManager.this::updateSidebar), 0, 5);
    }
  }

  protected void updateSidebar(User user, Sidebar sidebar) {
    List<String> formattedScoreboard = formatScoreboard(user);
    int size = formattedScoreboard.size();
    for (int i = 0 ; i < size; i++) {
      String line = formattedScoreboard.get(i);
      sidebar.setLine(size - i, new AnimatedLine(5, new AnimatedLineFrame(line)));
    }
  }

  /**
   * Creates arena scoreboard for target user
   *
   * @param user user that represents game player
   * @see User
   */
  public void createScoreboard(User user) {
    Sidebar sidebar = sidebarManager.createSidebar(user.getPlayer(), new AnimatedLine(-1, new AnimatedLineFrame(boardTitle)));
    sidebars.put(user, sidebar);
    updateSidebar(user, sidebar);
  }

  /**
   * Removes scoreboard of user
   *
   * @param user user that represents game player
   * @see User
   */
  public void removeScoreboard(User user) {
    sidebarManager.removeSidebar(user.getPlayer());
    sidebars.remove(user);
  }

  public void stopAllScoreboards() {
    stopAllScoreboards(false);
  }

  /**
   * Forces all scoreboards to deactivate.
   */
  public void stopAllScoreboards(boolean sync) {
    sidebars.forEach((user, sidebar) -> sidebarManager.removeSidebar(user.getPlayer(), sync));
    sidebars.clear();
  }

  private List<String> formatScoreboard(User user) {
    List<String> lines;
    if (arena.getArenaState() == ArenaState.IN_GAME || arena.getArenaState() == ArenaState.ENDING) {
      if (Role.isRole(Role.MURDERER, user.getPlayer())) {
        lines = LanguageManager.getLanguageList("Scoreboard.Content.Playing-Murderer");
      } else {
        lines = LanguageManager.getLanguageList("Scoreboard.Content.Playing");
      }
    } else {
      lines = LanguageManager.getLanguageList("Scoreboard.Content." + arena.getArenaState().getFormattedName());
    }
    return lines.stream().map(line -> formatScoreboardLine(line, user)).collect(Collectors.toList());
  }

  private String formatScoreboardLine(String line, User user) {
    line = StringUtils.replace(line, "%TIME%", String.valueOf(arena.getTimer()));
    line = StringUtils.replace(line, "%FORMATTED_TIME%", StringFormatUtils.formatIntoMMSS(arena.getTimer()));
    line = StringUtils.replace(line, "%MAPNAME%", arena.getMapName());

    if (!arena.getPlayersLeft().contains(user.getPlayer())) {
      line = StringUtils.replace(line, "%ROLE%", chatManager.colorMessage("Scoreboard.Roles.Dead"));
    } else {
      if (Role.isRole(Role.MURDERER, user.getPlayer())) {
        line = StringUtils.replace(line, "%ROLE%", chatManager.colorMessage("Scoreboard.Roles.Murderer"));
      } else if (Role.isRole(Role.ANY_DETECTIVE, user.getPlayer())) {
        line = StringUtils.replace(line, "%ROLE%", chatManager.colorMessage("Scoreboard.Roles.Detective"));
      } else {
        line = StringUtils.replace(line, "%ROLE%", chatManager.colorMessage("Scoreboard.Roles.Innocent"));
      }
    }

    int innocents = 0;
    for (Player currentPlayer : arena.getPlayersLeft()) {
      if (Role.isRole(Role.MURDERER, currentPlayer)) {
        continue;
      }
      innocents++;
    }
    line = StringUtils.replace(line, "%INNOCENTS%", String.valueOf(innocents));

    line = StringUtils.replace(line, "%PLAYERS%", String.valueOf(arena.getPlayers().size()));
    line = StringUtils.replace(line, "%MAX_PLAYERS%", String.valueOf(arena.getMaximumPlayers()));
    line = StringUtils.replace(line, "%MIN_PLAYERS%", String.valueOf(arena.getMinimumPlayers()));

    if (arena.isDetectiveDead() && !arena.isCharacterSet(Arena.CharacterType.FAKE_DETECTIVE)) {
      line = StringUtils.replace(line, "%DETECTIVE_STATUS%", chatManager.colorMessage("Scoreboard.Detective-Died-No-Bow"));
    }
    if (arena.isDetectiveDead() && arena.isCharacterSet(Arena.CharacterType.FAKE_DETECTIVE)) {
      line = StringUtils.replace(line, "%DETECTIVE_STATUS%", chatManager.colorMessage("Scoreboard.Detective-Died-Bow"));
    }
    if (!arena.isDetectiveDead()) {
      line = StringUtils.replace(line, "%DETECTIVE_STATUS%", chatManager.colorMessage("Scoreboard.Detective-Status-Normal"));
    }

    //should be for murderer only
    line = StringUtils.replace(line, "%KILLS%", String.valueOf(user.getStat(StatsStorage.StatisticType.LOCAL_KILLS)));
    line = StringUtils.replace(line, "%SCORE%", String.valueOf(user.getStat(StatsStorage.StatisticType.LOCAL_SCORE)));
    line = chatManager.colorRawMessage(line);

    if (plugin.getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
      line = PlaceholderAPI.setPlaceholders(user.getPlayer(), line);
    }
    return line;
  }

}
