package fiji.plugin.trackmate.gui.displaysettings;

import bacmman.ui.GUI;
import bacmman.ui.gui.TrackMateRunner;
import fiji.plugin.trackmate.Dimension;
import fiji.plugin.trackmate.Model;
import fiji.plugin.trackmate.Settings;
import fiji.plugin.trackmate.TrackMate;
import fiji.plugin.trackmate.features.spot.SpotAnalyzerFactory;
import fiji.plugin.trackmate.features.spot.SpotAnalyzerFactoryBase;
import fiji.plugin.trackmate.gui.displaysettings.DisplaySettings;
import org.slf4j.LoggerFactory;

import java.util.*;

public class DisplaySettingsLazy extends DisplaySettings {
    public static final org.slf4j.Logger logger = LoggerFactory.getLogger(TrackMateRunner.class);
    final TrackMate trackMate;
    int maxComputedFeature = -1;
    final Set<String> computedFeatures = new HashSet<>();
    public DisplaySettingsLazy(DisplaySettings ds, TrackMate trackMate) {
        super();
        this.trackMate = trackMate;
        set(ds);
    }
    @Override
    public synchronized void setSpotColorBy( final TrackMateObject spotColorByType, final String spotColorByFeature ) {
        ensureComputedFeature(spotColorByFeature);
        super.setSpotColorBy(spotColorByType, spotColorByFeature);
    }
    private void ensureComputedFeature(String feature) {
        if (!computedFeatures.contains(feature)) computeFeature(feature);
    }

    private void computeFeature(String feature) {
        logger.debug("feature {} will be computed. max computed feature idx : {}", feature, maxComputedFeature);
        // remove other feature analyser
        List<SpotAnalyzerFactoryBase<?>> safs = trackMate.getSettings().getSpotAnalyzerFactories();
        SpotAnalyzerFactoryBase<?> saf = safs.stream().filter(s -> s.getFeatures().contains(feature)).findFirst().orElse(null);
        if (saf!=null) {
            if (maxComputedFeature<0) declareFeatures();
            int idx = safs.indexOf(saf);
            if (idx<=maxComputedFeature) return;
            for (int i = 0; i<=maxComputedFeature; ++i) trackMate.getSettings().removeSpotAnalyzerFactory((SpotAnalyzerFactory<?>) safs.get(i));
            for (int i = idx+1; i<safs.size(); ++i) trackMate.getSettings().removeSpotAnalyzerFactory((SpotAnalyzerFactory<?>) safs.get(i));
            if (!trackMate.getSettings().getSpotAnalyzerFactories().isEmpty()) {
                trackMate.computeSpotFeatures(true);
                trackMate.getSettings().getSpotAnalyzerFactories().forEach(s -> computedFeatures.addAll(s.getFeatures()));
                trackMate.getSettings().clearSpotAnalyzerFactories();
            }
            safs.forEach(s -> trackMate.getSettings().addSpotAnalyzerFactory(s));
            maxComputedFeature = idx;
        }
    }

    private void declareFeatures() {
        for ( final SpotAnalyzerFactoryBase< ? > factory : trackMate.getSettings().getSpotAnalyzerFactories() )
        {
            final Collection< String > features = factory.getFeatures();
            final Map< String, String > featureNames = factory.getFeatureNames();
            final Map< String, String > featureShortNames = factory.getFeatureShortNames();
            final Map< String, Dimension> featureDimensions = factory.getFeatureDimensions();
            final Map< String, Boolean > isIntFeature = factory.getIsIntFeature();
            trackMate.getModel().getFeatureModel().declareSpotFeatures( features, featureNames, featureShortNames, featureDimensions, isIntFeature );
        }
    }
}
