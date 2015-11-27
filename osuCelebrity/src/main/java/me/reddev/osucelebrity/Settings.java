package me.reddev.osucelebrity;

import lombok.Getter;
import lombok.ToString;
import me.reddev.osucelebrity.core.CoreSettings;
import me.reddev.osucelebrity.osu.OsuApplication.OsuApplicationSettings;
import me.reddev.osucelebrity.osu.OsuIrcSettings;
import me.reddev.osucelebrity.osuapi.OsuApiSettings;
import me.reddev.osucelebrity.twitch.TwitchApiSettings;
import me.reddev.osucelebrity.twitch.TwitchIrcSettings;

import org.apache.commons.lang3.math.NumberUtils;

import java.io.InputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Properties;

@Getter
@ToString
public class Settings implements OsuIrcSettings, TwitchIrcSettings, TwitchApiSettings,
    OsuApplicationSettings, OsuApiSettings, CoreSettings {
  // Twitch IRC settings
  private String twitchIrcChannel;
  private String twitchIrcUsername;
  private String twitchToken;
  
  private String twitchIrcHost;
  private int twitchIrcPort;
  
  private String twitchIrcCommand;

  // Twitch API settings
  private String twitchApiRoot;
  
  // Osu! IRC settings
  private String osuIrcHost;
  private int osuIrcPort;
  private String osuCommandUser;
  private String osuIrcCommand;
  private boolean osuIrcSilenced;
  
  // Osu! API settings
  private String osuApiKey;

  // Osu! Account Settings
  private String osuIrcUsername;
  private String osuIrcPassword;
  private String osuIrcAutoJoin;
  
  private String twitchClientId;
  private String twitchClientSecret;

  // Osu! Location Settings
  private String osuPath;

  // Stream Output Settings
  private String streamOutputPath;

  // Application Settings
  private long defaultSpecDuration;
  private int apiPort;
  private long streamDelay;
  private long voteWindow;
  private long nextPlayerNotifyTime;
  private long autoSpecTime;
  private long offlineTimeout;
  private long idleTimeout;
  private long autoSpecMaxLastActivity;
  private long autoSpecMaxRank;
  private int minPlayCount;
  private long maxLastActivity;

  /**
   * Creates a new settings object using a given property list.
   * @param properties An imported java property list
   * @throws RuntimeException if an input parameter is not found 
   */
  public Settings(Properties properties) {
    Field[] fields = Settings.class.getDeclaredFields();
    for (Field f : fields) {
      Class<?> fieldClass = f.getType();
      if (f.getName().startsWith("$")) {
        continue;
      }
      String property = properties.getProperty(f.getName());
      
      if (property == null) {
        throw new RuntimeException("please supply the paramater " + f.getName());
      }
      
      try {
        if (fieldClass == String.class) {
          f.set(this, property);
        } else if (fieldClass == int.class) {
          try {
            f.set(this, NumberUtils.toInt(property));
          } catch (NumberFormatException e) {
            throw new NumberFormatException(f.getName() + " must be an integer");
          }
        } else if (fieldClass == long.class) {
          try {
            f.set(this, NumberUtils.toLong(property));
          } catch (NumberFormatException e) {
            throw new NumberFormatException(f.getName() + " must be a long");
          }
        } else if (fieldClass == boolean.class) {
          boolean value = Boolean.parseBoolean(property);
          if (!String.valueOf(value).equalsIgnoreCase(property)) {
            throw new IllegalArgumentException(f.getName() + " must be a boolean");
          }
          f.set(this, value);
        } else {
          throw new UnsupportedOperationException("could not parse object type " + f.getName());
        }
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public Settings() {
    this(getProperties("osuCelebrity.properties"));
  }
  
  /**
   * Loads a property from an included resource.
   * @param resourceName The name of the property resource
   * @return The property resource as a string
   */
  public static Properties getProperties(String resourceName) {
    InputStream is = Settings.class.getClassLoader().getResourceAsStream(resourceName);
    if (is == null) {
      throw new RuntimeException("resource " + resourceName + " not found");
    }

    Properties properties = new Properties();
    try {
      properties.load(is);
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      try {
        is.close();
      } catch (IOException e) {
        // suppress this
        e.printStackTrace();
      }
    }
    return properties;
  }
}
