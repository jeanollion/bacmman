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

import java.util.ArrayList;
import java.util.Collections;

/**
 *
 * @author Jean Ollion
 */
public abstract class CorrectionScenario {
        double cost=0;
        final int frameMin, frameMax;
        final BacteriaClosedMicrochannelTrackerLocalCorrections tracker;
        protected CorrectionScenario(int timePointMin, int timePointMax, BacteriaClosedMicrochannelTrackerLocalCorrections tracker) {
            this.frameMin=timePointMin; 
            this.frameMax=timePointMax;
            this.tracker= tracker;
        }
        protected abstract CorrectionScenario getNextScenario();
        /**
         * 
         * @param lengthLimit if >0 limits the length of the scenario
         * @param costLimit if >0 cost limit per operation
         * @param cumulativeCostLimit if >0 cost limit for the whole scenario
         * @return 
         */
        public CorrectionScenario getWholeScenario(FrameRange limit, int lengthLimit, double costLimit, double cumulativeCostLimit) {
            ArrayList<CorrectionScenario> res = new ArrayList<>();
            CorrectionScenario cur = this;
            if (cur instanceof MergeScenario && ((MergeScenario)cur).listO.isEmpty()) return new MultipleScenario(tracker, Collections.emptyList());
            double sum = 0;
            while(cur!=null && (!Double.isNaN(cur.cost)) && Double.isFinite(cur.cost) && cur.cost<costLimit && limit.isIncluded(cur.frameMax) && limit.isIncluded(cur.frameMin)) {
                res.add(cur);
                sum+=cur.cost;
                //if (cur.cost > costLimit) return new MultipleScenario(tracker, Collections.emptyList()); // if cost is beyond cost limit -> should scenario be considered ?
                if (cumulativeCostLimit>0 && sum>cumulativeCostLimit) return new MultipleScenario(tracker, Collections.emptyList());
                if (lengthLimit>0 && res.size()>=lengthLimit) return new MultipleScenario(tracker, Collections.emptyList());
                cur = cur.getNextScenario();
            }
            if (res.size()==1) return res.get(0);
            Collections.sort(res, (s1, s2)->Integer.compare(s1.frameMax, s2.frameMax));
            return new MultipleScenario(tracker, res);
        }
        protected abstract void applyScenario();
    }