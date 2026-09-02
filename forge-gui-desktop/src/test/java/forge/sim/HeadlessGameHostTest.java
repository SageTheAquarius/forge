package forge.sim;

import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.view.SimulateMatch;
import org.testng.annotations.Test;

/**
 * Proof-of-concept: drive a full headless Forge game with real shipped decks,
 * the way a server-side "game host" microservice would. Mirrors Main.main's
 * sim path (setInterface -> SimulateMatch.simulate) without System.exit.
 */
public class HeadlessGameHostTest {
    @Test(timeOut = 300000)
    public void runOneHeadlessGame() {
        GuiBase.setInterface(new GuiDesktop());
        // sim resolves -d names against %APPDATA%\Forge\decks\constructed\ (see
        // SimulateMatch.deckFromCommandLineParameter), so the decks are copied there.
        SimulateMatch.simulate(new String[]{"sim", "-d", "bluedragon.dck", "frogboss.dck", "-n", "1", "-c", "60"});
    }
}
