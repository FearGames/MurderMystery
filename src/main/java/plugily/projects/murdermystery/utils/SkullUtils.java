package plugily.projects.murdermystery.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import pl.plajerlair.commonsbox.minecraft.compat.XMaterial;

import java.util.UUID;

public final class SkullUtils {

  private static boolean usePlayerProfile;

  static {
    try {
      Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
      usePlayerProfile = true;
    } catch (Throwable t) {
      usePlayerProfile = false;
    }
  }

  private SkullUtils() {
  }

  public static void applyPlayerSkinToArmorStandHead(Plugin plugin, ArmorStand armorStand, UUID uniqueId) {
    ItemStack skull = XMaterial.PLAYER_HEAD.parseItem();
    SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
    if (usePlayerProfile) {
      PlayerProfile profile = Bukkit.createProfile(uniqueId);
      if (!profile.isComplete() || !profile.hasTextures()) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
          boolean result = profile.complete();
          Bukkit.getScheduler().runTask(plugin, () -> {
            if (!armorStand.isValid()) {
              return;
            }
            SkullMeta currentSkullMeta = (SkullMeta) skull.getItemMeta();
            if (result) {
              currentSkullMeta.setPlayerProfile(profile);
            } else {
              currentSkullMeta.setPlayerProfile(null);
            }
            skull.setItemMeta(currentSkullMeta);
            armorStand.setHelmet(skull);
          });
        });
      } else {
        skullMeta.setPlayerProfile(profile);
        skull.setItemMeta(skullMeta);
        armorStand.setHelmet(skull);
      }
    } else {
      // FIXME: blocking call
      skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(uniqueId));
      skull.setItemMeta(skullMeta);
      armorStand.setHelmet(skull);
    }
  }

  public static void applyPlayerSkinToSkullAsync(Plugin plugin, ItemStack skull, UUID uniqueId) {
    ItemMeta itemMeta = skull.getItemMeta();
    if (!(itemMeta instanceof SkullMeta)) {
      return;
    }
    SkullMeta skullMeta = (SkullMeta) itemMeta;
    if (usePlayerProfile) {
      PlayerProfile profile = Bukkit.createProfile(uniqueId);
      if (!profile.isComplete() || !profile.hasTextures()) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
          boolean result = profile.complete();
          Bukkit.getScheduler().runTask(plugin, () -> {
            SkullMeta currentSkullMeta = (SkullMeta) skull.getItemMeta();
            if (result) {
              currentSkullMeta.setPlayerProfile(profile);
            } else {
              currentSkullMeta.setPlayerProfile(null);
            }
            skull.setItemMeta(currentSkullMeta);
          });
        });
      } else {
        skullMeta.setPlayerProfile(profile);
        skull.setItemMeta(skullMeta);
      }
    } else {
      // FIXME: blocking call
      skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(uniqueId));
      skull.setItemMeta(skullMeta);
    }
  }
}
