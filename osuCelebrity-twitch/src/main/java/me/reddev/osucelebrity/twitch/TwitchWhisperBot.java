package me.reddev.osucelebrity.twitch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.reddev.osucelebrity.AbstractIrcBot;
import org.apache.commons.lang3.tuple.Pair;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.hooks.events.ConnectEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map.Entry;

import javax.inject.Inject;

@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class TwitchWhisperBot extends AbstractIrcBot {
  public static final String ROOM = "#jtv";

  private final TwitchIrcSettings settings;

  /**
   * Sends a message to the current IRC channel.
   * 
   * @param message The message to send to the channel
   */
  public void whisper(String username, String message) {
    String command = String.format("PRIVMSG " + ROOM + " :/w %s %s", username, message);
    getBot().sendRaw().rawLineNow(command);
  }

  
  @Override
  protected Configuration getConfiguration() throws Exception {
    return new Configuration.Builder().setName(settings.getTwitchIrcUsername())
            .setLogin(settings.getTwitchIrcUsername()).addListener(this)
            .addServer(settings.getTwitchWhisperIrcHost(), settings.getTwitchWhisperIrcPort())
            .setServerPassword(settings.getTwitchToken())
            .setAutoReconnect(false)
            .addAutoJoinChannel(ROOM).buildConfiguration();
  }

  @Override
  public void onConnect(ConnectEvent event) throws Exception {
    super.onConnect(event);
    // Enables whispers to be received
    event.getBot().sendRaw().rawLineNow("CAP REQ :twitch.tv/commands");
  }
  
  @Override
  protected Logger getLog() {
    return log;
  }
}
