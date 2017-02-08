package me.reddev.osucelebrity.twitch;

import static me.reddev.osucelebrity.Commands.DOWNVOTE;
import static me.reddev.osucelebrity.Commands.UPVOTE;
import static me.reddev.osucelebrity.core.QQueuedPlayer.queuedPlayer;

import com.querydsl.jdo.JDOQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.reddev.osucelebrity.AbstractIrcBot;
import me.reddev.osucelebrity.Commands;
import me.reddev.osucelebrity.OsuResponses;
import me.reddev.osucelebrity.Responses;
import me.reddev.osucelebrity.TwitchResponses;
import me.reddev.osucelebrity.UserException;
import me.reddev.osucelebrity.core.Clock;
import me.reddev.osucelebrity.core.QueueVote;
import me.reddev.osucelebrity.core.QueuedPlayer;
import me.reddev.osucelebrity.core.QueuedPlayer.QueueSource;
import me.reddev.osucelebrity.core.Spectator;
import me.reddev.osucelebrity.core.Trust;
import me.reddev.osucelebrity.core.VoteType;
import me.reddev.osucelebrity.osu.Osu;
import me.reddev.osucelebrity.osu.OsuStatus;
import me.reddev.osucelebrity.osu.OsuStatus.Type;
import me.reddev.osucelebrity.osu.OsuUser;
import me.reddev.osucelebrity.osuapi.OsuApi;
import me.reddev.osucelebrity.twitchapi.TwitchApi;
import org.pircbotx.Configuration;
import org.pircbotx.hooks.events.JoinEvent;
import org.pircbotx.hooks.events.MessageEvent;
import org.slf4j.Logger;
import org.tillerino.osuApiModel.GameModes;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;

@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class TwitchIrcBot extends AbstractIrcBot {
  @FunctionalInterface
  interface CommandHandler {
    boolean handle(MessageEvent event, String message, String twitchUserName,
        PersistenceManager pm) throws UserException, IOException;
  }

  @FunctionalInterface
  interface ConfirmedCommandHandler {
    void handle(MessageEvent event, String arguments, String twitchUserName,
        PersistenceManager pm) throws UserException, IOException;
  }

  private final TwitchIrcSettings settings;

  private final OsuApi osuApi;

  private final TwitchApi twitchApi;
  
  private final Osu osu;

  private final PersistenceManagerFactory pmf;

  private final Spectator spectator;

  private final Clock clock;
  
  private final TwitchWhisperBot whisperBot;
  
  private final Trust trust;
  
  private final Twitch twitch;

  private final List<String> subscribers = new ArrayList<String>();

  private final List<CommandHandler> handlers = new ArrayList<>();

  {
    handlers.add(createHandler(false, this::handleQueue, Commands.QUEUE));
    handlers.add(this::handleVote);
    handlers.add(createHandler(false, this::handlePosition, Commands.POSITION));
    handlers.add(createHandler(false, this::handleNowPlaying, Commands.NOW_PLAYING));
    handlers.add(createHandler(false, this::handleReplaySpecific, Commands.REPLAY));
    handlers.add(createHandler(false, this::handleReplayCurrent, Commands.REPLAY_CURRENT));
    handlers.add(createHandler(false, this::handleLink, Commands.START_LINK));
    
    // MOD COMMANDS
    handlers.add(createHandler(true, this::handleAdvance, Commands.FORCESKIP));
    handlers.add(createHandler(true, this::handleForceSpec, Commands.FORCESPEC));
    handlers.add(createHandler(true, this::handleFixClient, Commands.RESTART_CLIENT));
    handlers.add(createHandler(true, this::handleBoost, Commands.BOOST));
    handlers.add(createHandler(true, this::handleTimeout, Commands.TIMEOUT));
    handlers.add(createHandler(true, this::handleBannedMapsFilter, Commands.ADD_BANNED_MAPS_FILTER));
    handlers.add(createHandler(true, this::handleGameMode, Commands.GAME_MODE));
    handlers.add(createHandler(true, this::handleExtend, Commands.EXTEND));
    handlers.add(createHandler(true, this::handleFreeze, Commands.FREEZE));
    handlers.add(createHandler(true, this::handleUnfreeze, Commands.UNFREEZE));
  }

  @Override
  protected Configuration getConfiguration() {
    return new Configuration.Builder()
            .setName(settings.getTwitchIrcUsername())
            .setLogin(settings.getTwitchIrcUsername())
            .addListener(this)
            .addServer(settings.getTwitchIrcHost(), settings.getTwitchIrcPort())
            .setServerPassword(settings.getTwitchToken())
            .setAutoReconnect(true)
            .addAutoJoinChannel(settings.getTwitchIrcChannel()).buildConfiguration();
  }

  /**
   * Sends a message to the current IRC channel.
   * 
   * @param message The message to send to the channel
   */
  public void sendMessage(String message) {
    getBot().sendIRC().message(settings.getTwitchIrcChannel(), message);
  }

  @Override
  public void onMessage(MessageEvent event) {
    String message = event.getMessage();
    // Search through for command calls
    if (!message.startsWith(settings.getTwitchIrcCommand())) {
      log.debug("Received Twitch message from {}: {}", event.getUserHostmask().getNick(), message);
      return;
    }

    message = message.substring(settings.getTwitchIrcCommand().length());

    PersistenceManager pm = pmf.getPersistenceManager();
    try {
      for (CommandHandler commandHandler : handlers) {
        if (commandHandler.handle(event, message, event.getUserHostmask().getNick(), pm)) {
          return;
        }
      }
      log.debug("Received unknown twitch command from {}: {}", event.getUserHostmask().getNick(), message);
    } catch (Exception e) {
      Consumer<String> messager =
          x -> whisperBot.whisper(event.getUserHostmask().getNick(), x);
      UserException.handleException(log, e, messager);
    } finally {
      pm.close();
    }
  }

  void handleQueue(MessageEvent event, String targetUser, String requesterNick,
      PersistenceManager pm) throws UserException, IOException {
    checkTrust(pm, requesterNick);
    
    // Permits: !spec username : reason
    // Example: !spec Tillerino: for awesomeness Keepo
    targetUser = targetUser.split(":")[0].trim();

    OsuUser requestedUser = getUserOrThrow(pm, targetUser, TimeUnit.DAYS.toMillis(1));
    QueuedPlayer queueRequest =
        new QueuedPlayer(requestedUser, QueueSource.TWITCH, clock.getTime());
    spectator.performEnqueue(pm, queueRequest, QueueVote.TWITCH + requesterNick, log,
        event::respond, msg -> whisperBot.whisper(requesterNick, msg));
  }

  void checkTrust(PersistenceManager pm, String subject) throws IOException, UserException {
    trust.checkTrust(pm, twitch.getUser(pm, subject, 60 * 1000L, true));
  }

  boolean handleVote(MessageEvent event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    VoteType type = null;
    if (Commands.detect(message, UPVOTE) != null) {
      type = VoteType.UP;
    }
    if (Commands.detect(message, DOWNVOTE) != null) {
      type = VoteType.DOWN;
    }
    if (type == null) {
      return false;
    }
    log.debug("{} votes {}", twitchUserName, type);

    checkTrust(pm, twitchUserName);

    spectator.vote(pm, twitchUserName, type, event.getMessage());
    return true;
  }
  
  void requireMod(MessageEvent event) throws UserException {
    if (!twitchApi.isModerator(event.getUserHostmask().getNick())) {
      throw new UserException("You're not a mod.");
    }
  }

  void handleAdvance(MessageEvent event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    if (spectator.advanceConditional(pm, message)) {
      sendMessage(String.format(TwitchResponses.SKIPPED_FORCE, message, event.getUserHostmask().getNick()));
    }
  }
  
  void handleForceSpec(MessageEvent event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    OsuUser ircUser = getUserOrThrow(pm, message, TimeUnit.DAYS.toMillis(1));
    if (spectator.promote(pm, ircUser)) {
      sendMessage(String.format(TwitchResponses.SPECTATE_FORCE,
          event.getUserHostmask().getNick(), message));
    }
  }

  @Nonnull
  private OsuUser getUserOrThrow(PersistenceManager pm, String username, long maxAge)
      throws IOException, UserException {
    OsuUser ircUser = osuApi.getUser(username, pm, maxAge);
    if (ircUser == null) {
      throw new UserException(String.format(Responses.INVALID_USER, username));
    }
    return ircUser;
  }
  
  void handlePosition(MessageEvent event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    OsuUser ircUser = getUserOrThrow(pm, message, 0);
    int position = spectator.getQueuePosition(pm, ircUser);
    if (position != -1) {
      event.getChannel().send().message(String.format(OsuResponses.POSITION, 
          ircUser.getUserName(), position));
    } else {
      throw new UserException(String.format(OsuResponses.NOT_IN_QUEUE, 
          ircUser.getUserName()));
    }
  }
  
  void handleNowPlaying(MessageEvent event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    QueuedPlayer player = spectator.getCurrentPlayer(pm);
    if (player == null) {
      return;
    }
    
    OsuStatus status = osu.getClientStatus();
    String detail = status.getDetail();
    if (status.getType() != Type.PLAYING || detail == null) {
      return;
    }
    
    String formatted =
        String.format(OsuResponses.NOW_PLAYING, player.getPlayer().getUserName(),
                player.getPlayer().getUserId(), detail);
    Integer beatmapId = osu.getBeatmapId(detail);
    if (beatmapId != null) {
      formatted += " https://osu.ppy.sh/b/" + beatmapId; 
    }
    event.getChannel().send().message(formatted);
  }
  
  void handleFixClient(MessageEvent event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    try {
      osu.restartClient();
    } catch (InterruptedException e) {
      // w/e
    }
  }
  
  void handleBoost(MessageEvent event, String boostedUser, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    spectator.boost(pm, getUserOrThrow(pm, boostedUser, 0));

    event.getChannel().send()
        .message(String.format(TwitchResponses.BOOST_QUEUE, boostedUser, event.getUserHostmask().getNick()));
  }
  
  void handleTimeout(MessageEvent event, String message, String twitchUserName,
      PersistenceManager pm) throws UserException, IOException {
    String[] split = message.split(" ", 2);
    
    int minutes = Math.max(0, Integer.parseInt(split[0]));
    OsuUser timeoutUser = getUserOrThrow(pm, split[1], 0);
    
    timeoutUser.setTimeOutUntil(clock.getTime() + 60L * 1000 * minutes);
    
    event.getChannel().send()
        .message(String.format(TwitchResponses.TIMEOUT, timeoutUser.getUserName(), minutes));
  }
  
  void handleBannedMapsFilter(MessageEvent event, String message,
      String twitchUserName, PersistenceManager pm) throws UserException, IOException {
    spectator.addBannedMapFilter(pm, message);

    event.getChannel().send().message(String.format(TwitchResponses.ADDED_BANNED_MAPS_FILTER));
  }
  
  void handleGameMode(MessageEvent event, String message,
      String twitchUserName, PersistenceManager pm) throws UserException, IOException {
    String[] split = message.split(" ", 2);

    OsuUser user = getUserOrThrow(pm, split[1], 0);
    
    if (split[0].equalsIgnoreCase("osu")) {
      user.setGameMode(GameModes.OSU);
    } else if (split[0].equalsIgnoreCase("taiko")) {
      user.setGameMode(GameModes.TAIKO);
    } else if (split[0].equalsIgnoreCase("ctb")) {
      user.setGameMode(GameModes.CTB);
    } else if (split[0].equalsIgnoreCase("mania")) {
      user.setGameMode(GameModes.MANIA);
    } else {
      throw new UserException("Unknown game mode.");
    }
    
    event.getChannel().send().message(Responses.GAME_MODE_CHANGED);
  }
  
  void handleExtend(MessageEvent event, String targetUser,
      String twitchUserName, PersistenceManager pm) throws UserException, IOException {
    spectator.extendConditional(pm, targetUser);
  }

  void handleFreeze(MessageEvent event, String message,
      String twitchUserName, PersistenceManager pm) throws UserException, IOException {
    spectator.setFrozen(true);
  }

  void handleUnfreeze(MessageEvent event, String message,
      String twitchUserName, PersistenceManager pm) throws UserException, IOException {
    spectator.setFrozen(false);
  }
  
  void handleLink(MessageEvent event, String message,
      String twitchUserName, PersistenceManager pm) throws UserException, IOException {
    TwitchUser user = twitch.getUser(pm, twitchUserName, 60 * 1000L, true);
    
    if (user.getOsuUser() != null) {
      throw new UserException("Your account is already linked.");
    }
    
    user.setLinkString(UUID.randomUUID().toString());
    
    whisperBot.whisper(twitchUserName,
        String.format(TwitchResponses.LINK_INSTRUCTIONS, user.getLinkString()));
  }

  void handleReplayCurrent(MessageEvent event, String message,
      String twitchUserName, PersistenceManager pm) throws UserException, IOException {
    QueuedPlayer currentPlayer = spectator.getCurrentPlayer(pm);
    if (currentPlayer == null) {
      return;
    }
    
    URL replayLink = twitchApi.getReplayLink(currentPlayer);
    if (replayLink == null) {
      throw new UserException(String.format(Responses.REPLAY_NOT_FOUND, currentPlayer.getPlayer()
          .getUserName()));
    }
    
    event
        .getChannel()
        .send()
        .message(
            String.format(TwitchResponses.REPLAY, currentPlayer.getPlayer().getUserName(),
                replayLink.toString()));
  }

  void handleReplaySpecific(MessageEvent event, String targetPlayer,
      String twitchUserName, PersistenceManager pm) throws UserException, IOException {
    QueuedPlayer player;
    try (JDOQuery<QueuedPlayer> query = new JDOQuery<>(pm).select(queuedPlayer).from(queuedPlayer)) {
      player =
          query
              .where(
                  queuedPlayer.player.userName.toLowerCase().eq(targetPlayer.trim().toLowerCase()),
                  queuedPlayer.state.loe(QueuedPlayer.SPECTATING)).orderBy(queuedPlayer.id.desc())
              .fetchFirst();
    }
    
    if (player == null) {
      throw new UserException(String.format(Responses.REPLAY_NOT_FOUND, targetPlayer));
    }
    
    URL replayLink = twitchApi.getReplayLink(player);
    if (replayLink == null) {
      throw new UserException(String.format(Responses.REPLAY_NOT_FOUND, player.getPlayer().getUserName()));
    }
    
    event
        .getChannel()
        .send()
        .message(
            String.format(TwitchResponses.REPLAY, player.getPlayer().getUserName(),
                replayLink.toString()));
  }

  @Override
  public void onJoin(JoinEvent event) {
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
  
  CommandHandler createHandler(boolean requiresMod, ConfirmedCommandHandler handler,
      String... triggers) {
    return (event, message, twitchUserName, pm) -> {
      String remainingMessage = Commands.detect(message, triggers);
      if (remainingMessage == null) {
        return false;
      }
      if (requiresMod) {
        requireMod(event);
      }
      log.debug("{} invokes {}{}", twitchUserName, settings.getTwitchIrcCommand(), message);
      handler.handle(event, remainingMessage, twitchUserName, pm);
      return true;
    };
  }
  
  @Override
  protected Logger getLog() {
    return log;
  }
}
