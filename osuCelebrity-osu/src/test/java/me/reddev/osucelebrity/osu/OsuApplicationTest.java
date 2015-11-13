package me.reddev.osucelebrity.osu;

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;
import me.reddev.osucelebrity.osu.OsuApplication.OsuApplicationSettings;
import me.reddev.osucelebrity.osu.OsuStatus.Type;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


public class OsuApplicationTest {
  @Mock
  OsuApplicationSettings settings;

  @Before
  public void initMocks() {
    MockitoAnnotations.initMocks(this);

  }

  String windowTitle;
  OsuApplication fakeWindowTitle = new OsuApplication(null) {
    public String getWindowTitle() {
      return OsuApplicationTest.this.windowTitle;
    }
  };

  @Test
  public void testWindowTitleWatching() throws Exception {
    windowTitle = "osu!  -  (watching hvick225)";
    assertEquals(new OsuStatus(Type.WATCHING, "hvick225"), fakeWindowTitle.getStatus());
  }

  @Test
  public void testWindowTitlePlaying() throws Exception {
    windowTitle = "osu!  - Ni-Sokkususu - Shukusai no Elementalia [GAPS 'n' JUMPS!]";
    assertEquals(new OsuStatus(Type.PLAYING,
        "Ni-Sokkususu - Shukusai no Elementalia [GAPS 'n' JUMPS!]"), fakeWindowTitle.getStatus());
  }

  public static void main(String[] args) throws Exception {
    System.out.println(new OsuApplication(null).readWindowTitle());
  }
}
