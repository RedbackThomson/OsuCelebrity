package me.reddev.osucelebrity.twitch;

import static me.reddev.osucelebrity.twitchapi.QTwitchApiUser.twitchApiUser;

import com.querydsl.jdo.JDOQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.reddev.osucelebrity.core.Clock;
import me.reddev.osucelebrity.core.QueuedPlayer;
import me.reddev.osucelebrity.core.StatusWindow;
import me.reddev.osucelebrity.twitch.api.Kraken;
import me.reddev.osucelebrity.twitch.api.KrakenVideo;
import me.reddev.osucelebrity.twitchapi.TwitchApi;
import me.reddev.osucelebrity.twitchapi.TwitchApiUser;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Mappings;

import java.io.IOException;
import java.net.URL;
import java.util.Optional;
import java.util.function.Supplier;

import javax.inject.Inject;
import javax.jdo.PersistenceManager;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.WebApplicationException;

@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class TwitchApiImpl implements TwitchApi {
  @Mapper
  public interface FromApiMapper {
    @Mappings({@Mapping(target = "id", ignore = true)})
    void update(TwitchApiUser user, @MappingTarget TwitchApiUser target);
  }

  private final TwitchIrcSettings ircSettings;
  private final Clock clock;
  private final StatusWindow statusWindow;
  private final Kraken kraken;

  @Override
  public TwitchApiUser getUser(PersistenceManager pm, String username, long maxAge,
      boolean returnCachedOnIoException) {
    try (JDOQuery<TwitchApiUser> query =
        new JDOQuery<>(pm).select(twitchApiUser).from(twitchApiUser)
            .where(twitchApiUser.name.eq(username))) {
      TwitchApiUser cached = query.fetchOne();
      
      if (cached == null) {
        TwitchApiUser downloaded = downloadUser(username);
        // let's look for a name change :/
        try (JDOQuery<TwitchApiUser> queryById =
            new JDOQuery<>(pm).select(twitchApiUser).from(twitchApiUser)
                .where(twitchApiUser.id.eq(downloaded.getId()))) {
          cached = queryById.fetchOne();
          if (cached != null) {
            new FromApiMapperImpl().update(downloaded, cached);
            return cached;
          }
        }
        return pm.makePersistent(downloaded);
      }
      
      if (maxAge <= 0 || cached.getDownloaded() > clock.getTime() - maxAge) {
        return cached;
      }
      
      try {
        new FromApiMapperImpl().update(downloadUser(username), cached);
      } catch (ServerErrorException e) {
        if (returnCachedOnIoException) {
          log.warn("API caused exception. Using cached data.", e);
          return cached;
        }
        throw e;
      }
      return cached;
    }
  }

  private TwitchApiUser downloadUser(String username) {
    TwitchApiUser user = kraken.getUser(username);
    user.setDownloaded(clock.getTime());
    return user;
  }
  
  @Override
  public URL getReplayLink(QueuedPlayer play) throws IOException {
    Optional<KrakenVideo> match =
        kraken
            .getChannel(ircSettings.getTwitchIrcUsername().toLowerCase())
            .getVideos(true, 100)
            .getVideos()
            .stream()
            .filter(video -> video.getRecordedAt().getTime() < play.getStartedAt())
            .filter(
                video -> video.getRecordedAt().getTime() + video.getLength() * 1000 > play
                    .getStartedAt() || video.getStatus().equals("recording")).findFirst();

    if (!match.isPresent()) {
      return null;
    }

    String offset = getOffset(play.getStartedAt() - match.get().getRecordedAt().getTime());
    return new URL(match.get().getUrl() + "?t=" + offset);
  }
  
  String getOffset(long timeInMillis) {
    timeInMillis /= 1000;
    String offset = StringUtils.leftPad(String.valueOf(timeInMillis % 60), 2, '0') + "s";

    timeInMillis /= 60;
    if (timeInMillis <= 0) {
      return offset;
    }
    offset = StringUtils.leftPad(String.valueOf(timeInMillis % 60), 2, '0') + "m" + offset;

    timeInMillis /= 60;
    if (timeInMillis <= 0) {
      return offset;
    }
    offset = StringUtils.leftPad(String.valueOf(timeInMillis), 2, '0') + "h" + offset;

    return offset;
  }
  
  /**
   * Performs a call and rewraps JAX-RS IOExceptions into {@link ServiceUnavailableException}.
   * @throws WebApplicationException all IOExceptions are wrapped in
   */
  public static <T> T rewrapIoExceptions(Supplier<T> call) throws WebApplicationException {
    try {
      return call.get();
    } catch (ProcessingException e) {
      Throwable cause = e.getCause();
      if (cause == null) {
        throw e;
      }
      if (!(cause instanceof IOException)) {
        throw e;
      }
      throw new ServiceUnavailableException(e.getMessage());
    }
  }
}
