/* 
 * Copyright (C) 2018 Jean Ollion
 *
 * This File is part of BACMMAN
 *
 * BACMMAN is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * BACMMAN is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with BACMMAN.  If not, see <http://www.gnu.org/licenses/>.
 */
package bacmman.plugins.plugins.trackers.bacteria_in_microchannel_tracker;

import bacmman.utils.Utils;

import static bacmman.utils.Utils.parallel;
import java.util.List;

/**
 *
 * @author Jean Ollion
 */
public class MultipleScenario extends CorrectionScenario {
        final List<CorrectionScenario> scenarios;

        public MultipleScenario(BacteriaClosedMicrochannelTrackerLocalCorrections tracker, List<CorrectionScenario> sortedScenarios) {
            super(sortedScenarios.isEmpty()? 0 :sortedScenarios.get(0).frameMin, sortedScenarios.isEmpty()? 0 : sortedScenarios.get(sortedScenarios.size()-1).frameMax, tracker);
            this.scenarios = sortedScenarios;
            if (scenarios.isEmpty()) this.cost = Double.POSITIVE_INFINITY;
            else for (CorrectionScenario s : scenarios) this.cost+=s.cost;
        }
        
        @Override
        protected CorrectionScenario getNextScenario() {
            return null;
        }

        @Override
        protected void applyScenario() {
            Utils.parallel(scenarios.stream(), !BacteriaClosedMicrochannelTrackerLocalCorrections.performSeveralIntervalsInParallel).forEach(s -> s.applyScenario());
        }
        @Override 
        public String toString() {
            return "MultipleScenario ["+frameMin+";"+frameMax+"]" + (this.scenarios.isEmpty() ? "": " first: "+scenarios.get(0).toString());
        }
    }
