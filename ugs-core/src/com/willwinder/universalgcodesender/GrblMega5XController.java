/*
    Copyright 2021 Will Winder

    This file is part of Universal Gcode Sender (UGS).

    UGS is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    UGS is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with UGS.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.willwinder.universalgcodesender;

import java.util.Optional;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.willwinder.universalgcodesender.CapabilitiesConstants.*;

/**
 *
 * @author AndyCXL
 */

public class GrblMega5XController extends GrblController {
    private final Capabilities capabilities = new Capabilities();
    private static final Logger logger = Logger.getLogger(GrblMega5XController.class.getName());
    
    static Pattern axisCountPattern = Pattern.compile("\\[AXS:(\\d*):([XYZABC]*)]");

    public GrblMega5XController() {
        super();
        this.capabilities.addCapability(GrblCapabilitiesConstants.V1_FORMAT);
    }

    Optional<Integer> getAxisCount(String response) {
        Matcher m = axisCountPattern.matcher(response);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(m.group(1)));
    }

    Optional<String> getAxisOrder(String response) {
        Matcher m = axisCountPattern.matcher(response);
        if (!m.find()) {
            return Optional.empty();
        }
        return Optional.of(m.group(2));
    }
    
    @Override
    public Capabilities getCapabilities() {
        return capabilities.merge(super.getCapabilities());
    }

    @Override
    protected void openCommAfterEvent() {
        // This doesn't seem to be required, but it's an option.
        //this.comm.queueCommand(new GcodeCommand("[ESP444]RESTART"));
        //this.comm.streamCommands();
    }

    @Override
    protected void rawResponseHandler(String response) {
        /*
        [VER:1.1t.20210510:]
        [AXS:5:XYZYA]
        [OPT:VNMGPH,25,255,48]
         */
        Optional<Integer> axes = getAxisCount(response);
        Optional<String> order = getAxisOrder(response);
        
        if (axes.isPresent()) {
            logger.info("Axis Count: " + axes.get() + " Order: " + order.get());
            
            this.capabilities.removeCapability(X_AXIS);
            this.capabilities.removeCapability(Y_AXIS);
            this.capabilities.removeCapability(Z_AXIS);
            this.capabilities.removeCapability(A_AXIS);
            this.capabilities.removeCapability(B_AXIS);
            this.capabilities.removeCapability(C_AXIS);

            /* Axes defined the number of distinct axes, order defines the
            // sequence and any duplication, eg: dual-Y or dual-X configs
            // eg: 3=XYA 3 distinct, or 5=XYZYA 4 distinct
            // Ignore for now that Mega 5X allows letters other than ABC
            //
            // Strategy for coping with non XYZABC order, and duplicates:
            // Iterate 1..axes.get()
            //   Obtain nth axis letter from order.get()
            //   Identify relevant n_AXIS enum
            //   Test if this.capabilities.hasCapability() already exists
            //   addCapability() if not exists
            */
            char nthChar;
            for (int n=1; n <= axes.get(); n++) {
                nthChar = Character.toUpperCase(order.get().charAt(n));
                switch(nthChar) {
                    case 'X':
                        if (!this.capabilities.hasCapability(X_AXIS))
                            this.capabilities.addCapability(X_AXIS);
                        break;
                    case 'Y':
                        if (!this.capabilities.hasCapability(Y_AXIS))
                            this.capabilities.addCapability(Y_AXIS);
                        break;
                    case 'Z':
                        if (!this.capabilities.hasCapability(Z_AXIS))
                            this.capabilities.addCapability(Z_AXIS);
                        break;
                    case 'A':
                        if (!this.capabilities.hasCapability(A_AXIS))
                            this.capabilities.addCapability(A_AXIS);
                        break;
                    case 'B':
                        if (!this.capabilities.hasCapability(B_AXIS))
                            this.capabilities.addCapability(B_AXIS);
                        break;
                    case 'C':
                        if (!this.capabilities.hasCapability(C_AXIS))
                            this.capabilities.addCapability(C_AXIS);
                        break;
                }
            }
        }

        super.rawResponseHandler(response);
    }
}
