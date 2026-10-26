package lk.sahan.parking.desktop;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;
import java.util.List;
import java.util.Objects;

/** Absolute classpath URLs work from Maven classes and from packaged JARs. */
final class BrandAssets {
    static final List<Integer> ICON_SIZES = List.of(16, 24, 32, 48, 64, 128, 256);
    private static final String ROOT = "/lk/sahan/parking/desktop/branding/";
    private static final List<Image> ICONS = ICON_SIZES.stream()
            .map(size -> load("park-lanka-icon-" + size + ".png")).toList();
    private static final Image LOGO = load("park-lanka-logo.png");

    private BrandAssets() {}

    private static Image load(String file) {
        Image image = new Image(Objects.requireNonNull(BrandAssets.class.getResource(ROOT + file),
                "Missing Park Lanka artwork: " + file).toExternalForm());
        if (image.isError()) throw new IllegalStateException("Cannot load Park Lanka artwork: " + file, image.getException());
        return image;
    }

    static ImageView logo() { return view(LOGO, 340, "Park Lanka — Smart Parking. Simple Control."); }
    static ImageView sidebarIcon() { return view(ICONS.get(4), 40, "Park Lanka"); }
    static void apply(Stage stage) { stage.getIcons().setAll(ICONS); }

    private static ImageView view(Image image, double width, String description) {
        ImageView view = new ImageView(image);
        view.setPreserveRatio(true); view.setSmooth(true); view.setFitWidth(width);
        view.setAccessibleText(description);
        return view;
    }
}
