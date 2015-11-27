package me.reddev.osucelebrity.twitch;

import static me.reddev.osucelebrity.Commands.DOWNVOTE;
import static me.reddev.osucelebrity.Commands.FORCESKIP;
import static me.reddev.osucelebrity.Commands.FORCESPEC;
import static me.reddev.osucelebrity.Commands.NOW_PLAYING;
import static me.reddev.osucelebrity.Commands.POSITION;
import static me.reddev.osucelebrity.Commands.QUEUE;
import static me.reddev.osucelebrity.Commands.UPVOTE;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.reddev.osucelebrity.OsuResponses;
import me.reddev.osucelebrity.Responses;
import me.reddev.osucelebrity.TwitchResponses;
import me.reddev.osucelebrity.UserException;
import me.reddev.osucelebrity.core.Clock;
import me.reddev.osucelebrity.core.EnqueueResult;
import me.reddev.osucelebrity.core.QueuedPlayer;
import me.reddev.osucelebrity.core.QueuedPlayer.QueueSource;
import me.reddev.osucelebrity.core.Spectator;
import me.reddev.osucelebrity.core.VoteType;
import me.reddev.osucelebrity.osu.Osu;
import me.reddev.osucelebrity.osu.OsuStatus;
import me.reddev.osucelebrity.osu.OsuStatus.Type;
import me.reddev.osucelebrity.osu.OsuUser;
import me.reddev.osucelebrity.osuapi.OsuApi;

import org.apache.commons.lang3.StringUtils;
import org.pircbotx.Configuration;
import org.pircbotx.PircBotX;
import org.pircbotx.hooks.ListenerAdapter;
import org.pircbotx.hooks.events.ConnectEvent;
import org.pircbotx.hooks.events.DisconnectEvent;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.pircbotx.hooks.events.PrivateMessageEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.inject.Inject;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;

@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class TwitchIrcBot extends ListenerAdapter<PircBotX> implements Runnable {
  @FunctionalInterface
  interface CommandHandler {
    boolean handle(MessageEvent<PircBotX> event, String message, String twitchUserName,
        PersistenceManager pm) throws UserException, IOException;
  }

  private final TwitchIrcSettings settings;

  private final OsuApi osuApi;

  private final Twitch twitch;
  
  private final Osu osu;

  private final PersistenceManagerFactory pmf;

  private final Spectator spectator;

  private final Clock clock;

  PircBotX bot;

  private final List<String> subscribers = new ArrayList<String>();

  private final List<CommandHandler> handlers = new ArrayList<>();

  {
    handlers.add(this::handleQueue);
    handlers.add(this::handleVote);
    handlers.add(this::handlePosition);
    handlers.add(this::handleNowPlaying);
  }
  
  private final List<CommandHandler> modHandlers = new ArrayList<>();
  
  {
    modHandlers.add(this::handleAdvance);
    modHandlers.add(this::handleSpec);
  }

  @Override
  public void run() {
    try {
      Configuration<PircBotX> config =
          new Configuration.Builder<PircBotX>()
              .setName(settings.getTwitchIrcUsername())
              .setLogin(settings.getTwitchIrcUsername())
              .addListener(this)
              .setServer(settings.getTwitchIrcHost(), settings.getTwitchIrcPort(),
                  settings.getTwitchToken()).setAutoReconnect(true)
              .addAutoJoinChannel(settings.getTwitchIrcChannel()).buildConfiguration();
      bot = new PircBotX(config);

      bot.startBot();
    } catch (Exception e) {
      log.error("Exception", e);
    }
  }

  /**
   * Disconnects from the IRC server.
   */
  public void stop() {
    if (bot.isConnected()) {
      bot.stopBotReconnect();
    }
  }

  /**
   * Sends a message to the current IRC channel.
   * 
   * @param message The message to send to the channel
   */
  public void sendMessage(String message) {
    bot.sendIRC().message(settings.getTwitchIrcChannel(), message);
  }

  // Listeners
  // http://site.pircbotx.googlecode.com/hg-history/2.0.1/apidocs/index.html

  @Override
  public void onConnect(ConnectEvent<PircBotX> event) throws Exception {
    log.debug("connected");
  }

  @Override
  public void onDisconnect(DisconnectEvent<PircBotX> event) throws Exception {
    log.debug("disconnected");
  }

  @Override
  public void onMessage(MessageEvent<PircBotX> event) {
    String message = event.getMessage();
    log.debug("Received Twitch message: " + message);
    // Search through for command calls
    if (!message.startsWith(settings.getTwitchIrcCommand())) {
      return;
    }

    message = message.substring(settings.getTwitchIrcCommand().length());

    PersistenceManager pm = pmf.getPersistenceManager();
    try {
      boolean handled = false;
      
      if (event.getChannel().isOp(event.getUser())) {
        for (CommandHandler commandHandler : modHandlers) {
          if (commandHandler.handle(event, message, event.getUser().getNick(), pm)) {
            handled = true;
            break;
          }
        }
      }
      
      if (!handled) {
        for (CommandHandler commandHandler : handlers) {
          if (commandHandler.handle(event, message, event.getUser().getNick(), pm)) {
            break;
          }
        }
      }
    } catch (Exception e) {
      Consumer<String> messager =
          x -> event.getChannel().send()
              .message(String.format("@%s: %s", event.getUser().getNick(), x));
      UserException.handleException(log, e, messager);
    } finally {
      pm.close();
    }
  }

  boolean handleQueue(MessageEvent<PircBotX> event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    if (StringUtils.startsWithIgnoreCase(message, QUEUE)) {
      String targetUser = message.substring(QUEUE.length());
      OsuUser requestedUser = osuApi.getUser(targetUser, pm, 60 * 60 * 1000L);
      if (requestedUser == null) {
        throw new UserException(String.format(TwitchResponses.INVALID_USER, targetUser));
      }
      QueuedPlayer queueRequest =
          new QueuedPlayer(requestedUser, QueueSource.TWITCH, clock.getTime());
      EnqueueResult result = spectator.enqueue(pm, queueRequest, false, event.getUser().getNick());
      if (result == EnqueueResult.SUCCESS) {
        event.getChannel().send()
            .message(String.format(TwitchResponses.QUEUE_SUCCESSFUL, requestedUser.getUserName()));
      } else {
        event.getChannel().send().message(result.formatResponse(requestedUser.getUserName()));
      }
      return true;
    }
    return false;
  }

  boolean handleVote(MessageEvent<PircBotX> event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    VoteType type = null;
    if (message.equalsIgnoreCase(UPVOTE)) {
      type = VoteType.UP;
    }
    if (message.equalsIgnoreCase(DOWNVOTE)) {
      type = VoteType.DOWN;
    }
    if (type == null) {
      return false;
    }
    spectator.vote(pm, twitchUserName, type);
    return true;
  }

  boolean handleAdvance(MessageEvent<PircBotX> event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    if (!StringUtils.startsWithIgnoreCase(message, FORCESKIP)) {
      return false;
    }
    message = message.substring(FORCESKIP.length());
    
    if (spectator.advanceConditional(pm, message)) {
      sendMessage(String.format(TwitchResponses.SKIPPED_FORCE, message, event.getUser()
          .getNick()));
    }
    return true;
  }
  
  boolean handleSpec(MessageEvent<PircBotX> event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    if (!StringUtils.startsWithIgnoreCase(message, FORCESPEC)) {
      return false;
    }
    message = message.substring(FORCESPEC.length());
    
    OsuUser ircUser = osuApi.getUser(message, pm, 0);
    if (ircUser == null) {
      throw new UserException(String.format(Responses.INVALID_USER, message));
    }
    if (spectator.promote(pm, ircUser)) {
      sendMessage(String.format(TwitchResponses.SPECTATE_FORCE,
          event.getUser().getNick(), message));
    }
    return true;
  }
  
  boolean handlePosition(MessageEvent<PircBotX> event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    if (!StringUtils.startsWithIgnoreCase(message, POSITION)) {
      return false;
    }
    message = message.substring(POSITION.length());
    
    OsuUser ircUser = osuApi.getUser(message, pm, 0);
    if (ircUser != null) {
      int position = spectator.getQueuePosition(pm, ircUser);
      if (position != -1) {
        event.getChannel().send().message(String.format(OsuResponses.POSITION, 
            ircUser.getUserName(), position));
      } else {
        event.getChannel().send().message(String.format(OsuResponses.NOT_IN_QUEUE, 
            ircUser.getUserName()));
      }
      return true;
    }
    
    return false;
  }
  
  boolean handleNowPlaying(MessageEvent<PircBotX> event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    if (!StringUtils.startsWithIgnoreCase(message, NOW_PLAYING)) {
      return false;
    }
    
    QueuedPlayer player = spectator.getCurrentPlayer(pm);
    
    OsuStatus status = osu.getClientStatus();
    if (player != null && status != null && status.getType() == Type.PLAYING) {
      event.getChannel().send().message(String.format(OsuResponses.NOW_PLAYING, 
          player.getPlayer().getUserName(), status.getDetail()));
    }
    
    return true;
  }

  @Override 
  public void onPrivateMessage(PrivateMessageEvent<PircBotX> event) {
    String message = event.getMessage();
    Pattern specialUserRegex = Pattern.compile("SPECIALUSER ([^ ]+) (subscriber|turbo)");
    Matcher matches = specialUserRegex.matcher(message);

    // Nothing important - no special messages
    if (!matches.matches()) {
      return;
    }

    if (matches.group(2).equalsIgnoreCase("subscriber")) {
      subscribers.add(matches.group(1).toLowerCase());
    } else {
      // TODO: Add a special case for turbo users
    }
  }

  @Override
  public void onJoin(JoinEvent<PircBotX> event) {
    if (event.getUser().getLogin().equalsIgnoreCase(settings.getTwitchIrcUsername())) {
      // Ask for subscription and admin information
      event.getBot().sendRaw().rawLine("CAP REQ :twitch.tv/membership");
      log.info(String.format("Joined %s", event.getChannel().getName()));
    }
  }

  // End Listeners

  /**
   * @return The list of subscribers in the IRC channel.
   */
  public List<String> getSubscribers() {
    return subscribers;
  }
}
