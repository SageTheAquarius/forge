package forge.sim;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import org.jupnp.UpnpServiceConfiguration;

import forge.gamemodes.match.HostedMatch;
import forge.gui.FThreads;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.BuildInfo;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;

/**
 * The IGuiBase of a game host that has no user interface at all.
 *
 * ForgeServer drives every choice through PlayerControllerBridge, so nothing
 * in a bridged game should ever reach the dialog / skin / audio surface of
 * this interface. Everything here therefore either answers with the neutral
 * value (null, 0, false, the default option) or runs the work inline: there
 * is no event-dispatch thread to defer to.
 *
 * Two things do matter:
 *   - getAssetsDir(): where res/ lives. GuiDesktop derives it from the
 *     version string; here it is -Dforge.assets.dir (default "", i.e. the
 *     working directory, exactly what the prod jar relies on).
 *   - isRunningOnDesktop(): ForgeProfileProperties reads the writable
 *     userDir / cacheDir from -Dforge.ios.userDir / -Dforge.ios.cacheDir
 *     when this is false (upstream's iOS convention), and from APPDATA /
 *     ~/.forge when true.
 */
public final class GuiHeadless implements IGuiBase {
    private final String assetsDir;
    private final boolean desktop;
    private final ImageFetcher imageFetcher = new NoFetch();

    public GuiHeadless(final String assetsDir, final boolean runningOnDesktop) {
        this.assetsDir = withTrailingSlash(assetsDir == null ? "" : assetsDir);
        this.desktop = runningOnDesktop;
    }

    /** -Dforge.assets.dir and -Dforge.ios=true decide the two knobs. */
    public static GuiHeadless fromSystemProperties() {
        return new GuiHeadless(System.getProperty("forge.assets.dir", ""),
                !Boolean.getBoolean("forge.ios"));
    }

    private static String withTrailingSlash(final String dir) {
        if (dir.isEmpty() || dir.endsWith("/") || dir.endsWith(File.separator)) {
            return dir;
        }
        return dir + "/";
    }

    /** Never downloads anything: every image the phone has is bundled. */
    private static final class NoFetch extends ImageFetcher {
        @Override
        protected Runnable getDownloadTask(final String[] downloadUrls, final String destPath,
                final Runnable notifyObservers) {
            return () -> { };
        }
    }

    // ---- platform -------------------------------------------------------

    @Override public boolean isRunningOnDesktop() { return desktop; }
    @Override public boolean isLibgdxPort() { return false; }
    @Override public String getCurrentVersion() { return BuildInfo.getVersionString(); }
    @Override public String getAssetsDir() { return assetsDir; }
    @Override public ImageFetcher getImageFetcher() { return imageFetcher; }
    @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
    @Override public boolean hasNetGame() { return false; }
    @Override public float getScreenScale() { return 1f; }
    @Override public void preventSystemSleep(final boolean preventSleep) { }

    // ---- threading: there is no EDT, everything runs where it is called --

    @Override public void invokeInEdtNow(final Runnable proc) { proc.run(); }
    @Override public void invokeInEdtLater(final Runnable proc) { proc.run(); }
    @Override public void invokeInEdtAndWait(final Runnable proc) { proc.run(); }
    @Override public boolean isGuiThread() { return true; }
    @Override public void runBackgroundTask(final String message, final Runnable task) {
        FThreads.invokeInBackgroundThread(task);
    }

    // ---- skin / images: none ---------------------------------------------

    @Override public ISkinImage getSkinIcon(final FSkinProp skinProp) { return null; }
    @Override public ISkinImage getUnskinnedIcon(final String path) { return null; }
    @Override public ISkinImage getCardArt(final PaperCard card, final boolean backFace) { return null; }
    @Override public ISkinImage createLayeredImage(final PaperCard card, final FSkinProp background,
            final String overlayFilename, final float opacity) { return null; }
    @Override public void clearImageCache() { }
    @Override public String encodeSymbols(final String str, final boolean formatReminderText) { return str; }
    @Override public int getAvatarCount() { return 0; }
    @Override public int getSleevesCount() { return 0; }

    // ---- dialogs: answer with the neutral choice --------------------------

    @Override public void download(final GuiDownloadService service, final Consumer<Boolean> callback) {
        if (callback != null) {
            callback.accept(Boolean.FALSE);
        }
    }
    @Override public void copyToClipboard(final String text) { }
    @Override public void browseToUrl(final String url) { }
    @Override public void showCardList(final String title, final String message, final List<PaperCard> list) { }
    @Override public boolean showBoxedProduct(final String title, final String message, final List<PaperCard> list) {
        return false;
    }
    @Override public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) {
        System.err.println("[GuiHeadless] " + title + ": " + text);
    }
    @Override public void showImageDialog(final ISkinImage image, final String message, final String title) { }
    @Override public int showOptionDialog(final String message, final String title, final FSkinProp icon,
            final List<String> options, final int defaultOption) {
        return defaultOption;
    }
    @Override public String showInputDialog(final String message, final String title, final FSkinProp icon,
            final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        return initialInput;
    }
    @Override public String showFileDialog(final String title, final String defaultDir) { return null; }
    @Override public File getSaveFile(final File defaultFile) { return null; }

    @Override
    public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
            final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices) {
        final List<T> out = new ArrayList<>();
        if (destChoices != null) {
            out.addAll(destChoices);
        }
        if (sourceChoices != null) {
            out.addAll(sourceChoices);
        }
        return out;
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max,
            final Collection<T> choices, final Collection<T> selected,
            final FSerializableFunction<T, String> display) {
        final List<T> out = new ArrayList<>();
        if (selected != null) {
            out.addAll(selected);
        }
        if (out.size() < min && choices != null) {
            for (final T c : choices) {
                if (out.size() >= min) {
                    break;
                }
                if (!out.contains(c)) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    @Override public PaperCard chooseCard(final String title, final String message, final List<PaperCard> list) {
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    // ---- audio: none -----------------------------------------------------

    @Override public boolean isSupportedAudioFormat(final File file) { return false; }
    @Override public IAudioClip createAudioClip(final String filename) { return null; }
    @Override public IAudioMusic createAudioMusic(final String filename) { return null; }
    @Override public void startAltSoundSystem(final String filename, final boolean isSynchronized) { }

    // ---- screens / matches: the bridge builds its Match itself ------------

    @Override public void showSpellShop() { }
    @Override public void showBazaar() { }
    @Override public IGuiGame getNewGuiGame() { return null; }
    @Override public HostedMatch hostMatch() { return new HostedMatch(); }
}
