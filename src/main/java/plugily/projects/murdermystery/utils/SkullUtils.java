package plugily.projects.murdermystery.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.Server;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import pl.plajerlair.commonsbox.minecraft.compat.XMaterial;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class SkullUtils {

  private static final Gson GSON = new Gson();
  private static boolean usePlayerProfile;
  private static Cache<UUID, String> skinCache;

  static {
    /*
    try {
      Class.forName("com.destroystokyo.paper.profile.PlayerProfile");
      usePlayerProfile = true;
    } catch (Throwable t) {
      usePlayerProfile = false;
    }
    */
    usePlayerProfile = false; // FIXME: Currently disabled due to paper bug?
  }

  private SkullUtils() {
  }

  /**
   * Fetches a profile with skin using the paper PlayerProfile API, the request is performed asynchronously.
   *
   * @param plugin   the plugin instance.
   * @param uniqueId the uniqueId of the player.
   * @param callback the callback which will handle the result, called synchronously.
   */
  private static void fetchProfileWithSkin(Plugin plugin, UUID uniqueId, Consumer<Optional<PlayerProfile>> callback) {
    Server server = plugin.getServer();
    BukkitScheduler scheduler = server.getScheduler();

    PlayerProfile profile = server.createProfile(uniqueId);
    if (!profile.isComplete() || !profile.hasTextures()) {
      scheduler.runTaskAsynchronously(plugin, () -> {
        boolean result = profile.complete(true, true);
        scheduler.runTask(plugin, () -> callback.accept(result ? Optional.of(profile) : Optional.empty()));
      });
    } else {
      callback.accept(Optional.of(profile));
    }
  }

  /**
   * Fetches a profile with skin using the MojangAPI, the request is performed asynchronously.
   * Uses a cache to reduce external requests, TTL is hardcoded to 5 hours.
   *
   * @param plugin   the plugin instance.
   * @param uniqueId the uniqueId of the player.
   * @param callback the callback which will handle the result, called synchronously.
   */
  private static void fetchRawSkin(Plugin plugin, UUID uniqueId, Consumer<Optional<String>> callback) {
    if (skinCache == null) {
      skinCache = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.HOURS).build();
    }
    String cachedSkin = skinCache.getIfPresent(uniqueId);
    if (cachedSkin != null) {
      callback.accept(Optional.of(cachedSkin));
      return;
    }
    BukkitScheduler scheduler = plugin.getServer().getScheduler();
    scheduler.runTaskAsynchronously(plugin, () -> {
      String skin = null;
      try {
        JsonObject profile = getJsonFromUrl(new URL("https://sessionserver.mojang.com/session/minecraft/profile/"
          + uniqueId));
        // Check if the profile exists
        if (profile != null) {
          String encodedTextures = profile.getAsJsonArray("properties").get(0)
            .getAsJsonObject().get("value").getAsString();
          String texturesString = new String(Base64.getDecoder().decode(encodedTextures));
          JsonObject textures = GSON.fromJson(texturesString, JsonObject.class);
          String skinURL = textures.getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
          byte[] skinByte = ("{\"textures\":{\"SKIN\":{\"url\":\"" + skinURL + "\"}}}").getBytes();
          skin = new String(Base64.getEncoder().encode(skinByte));
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
      String finalSkin = skin;
      scheduler.runTask(plugin, () -> {
        if (finalSkin != null) {
          skinCache.put(uniqueId, finalSkin);
        }
        callback.accept(Optional.ofNullable(finalSkin));
      });
    });
  }

  /**
   * Fetches a JSON object from an external URL.
   *
   * @param url the source URL.
   * @return the JSON object.
   * @throws IOException when an IO error is raised.
   */
  private static JsonObject getJsonFromUrl(URL url) throws IOException {
    String content = getContentFromUrl(url);
    return GSON.fromJson(content, JsonObject.class);
  }

  /**
   * Fetches UTF-8 text from an external URL.
   *
   * @param url the source URL.
   * @return the text.
   * @throws IOException when an IO error is raised.
   */
  private static String getContentFromUrl(URL url) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    connection.setRequestMethod("GET");
    connection.connect();
    if (connection.getResponseCode() >= 300) {
      throw new IOException("Error while performing a HTTP request: "
        + connection.getResponseCode() + " " + connection.getResponseMessage());
    }
    try (
      InputStream inputStream = connection.getInputStream();
      InputStreamReader inputStreamReader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
      BufferedReader reader = new BufferedReader(inputStreamReader)
    ) {
      StringBuilder builder = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        builder.append(line);
      }
      return builder.toString();
    }
  }

  /**
   * Equips an head with a skin to an ArmorStand, the skin request is performed asynchronously and it will be
   * applied lazily.
   *
   * @param plugin     the plugin instance.
   * @param armorStand the ArmorStand entity.
   * @param uniqueId   the uniqueId of the player.
   */
  public static void applyPlayerSkinToArmorStandHead(Plugin plugin, ArmorStand armorStand, UUID uniqueId) {
    ItemStack skull = XMaterial.PLAYER_HEAD.parseItem();
    if (usePlayerProfile) {
      fetchProfileWithSkin(plugin, uniqueId, response -> response.ifPresent(profile -> {
        if (!armorStand.isValid()) {
          return;
        }
        SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
        skullMeta.setPlayerProfile(profile);
        skull.setItemMeta(skullMeta);
        armorStand.setHelmet(skull);
      }));
    } else {
      fetchRawSkin(plugin, uniqueId, response -> {
        response.ifPresent(skin -> {
          UUID hashAsId = new UUID(skin.hashCode(), skin.hashCode());
          plugin.getServer().getUnsafe().modifyItemStack(skull,
            "{SkullOwner:{Id:\"" + hashAsId + "\",Properties:{textures:[{Value:\"" + skin + "\"}]}}}");
          armorStand.setHelmet(skull);
        });
      });
    }
    armorStand.setHelmet(skull);
  }

  /**
   * Sets a player head's skin, the skin request is performed asynchronously and it will be applied lazily.
   *
   * @param plugin   the plugin instance.
   * @param skull    the skull ItemStack.
   * @param uniqueId the uniqueId of the player.
   */
  public static void applyPlayerSkinToSkullAsync(Plugin plugin, ItemStack skull, UUID uniqueId) {
    ItemMeta itemMeta = skull.getItemMeta();
    if (!(itemMeta instanceof SkullMeta)) {
      return;
    }
    if (usePlayerProfile) {
      fetchProfileWithSkin(plugin, uniqueId, profile -> {
        SkullMeta skullMeta = (SkullMeta) skull.getItemMeta();
        skullMeta.setPlayerProfile(profile.orElse(null));
        skull.setItemMeta(skullMeta);
      });
    } else {
      fetchRawSkin(plugin, uniqueId, response -> {
        response.ifPresent(skin -> {
          UUID hashAsId = new UUID(skin.hashCode(), skin.hashCode());
          plugin.getServer().getUnsafe().modifyItemStack(skull,
            "{SkullOwner:{Id:\"" + hashAsId + "\",Properties:{textures:[{Value:\"" + skin + "\"}]}}}");
        });
      });
    }
  }
}
