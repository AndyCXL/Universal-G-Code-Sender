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
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.MessageType;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UGSEvent;
import static com.willwinder.universalgcodesender.model.UGSEvent.ControlState.COMM_CHECK;
import com.willwinder.universalgcodesender.model.UnitUtils;
import java.util.logging.Level;

/**
 *
 * @author AndyCXL
 */

public class GrblMega5XController extends GrblController {
    
    private final Capabilities capabilities = new Capabilities();
    private Logger logger = Logger.getLogger(GrblMega5XController.class.getName());
    
    static String AxisOrder = null;
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

    // Grbl Mega5X can sequence axis letters differently to XYZABC
    public String getAxisLetterOrder(){
        return AxisOrder;    
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
            this.capabilities.removeCapability(X_AXIS);
            this.capabilities.removeCapability(Y_AXIS);
            this.capabilities.removeCapability(Z_AXIS);
            this.capabilities.removeCapability(A_AXIS);
            this.capabilities.removeCapability(B_AXIS);
            this.capabilities.removeCapability(C_AXIS);

            /* Axes defines the number of distinct axes, Order defines the
            // sequence and any duplication, eg: dual-Y or dual-X configs
            // eg: 3=XYZ 3 distinct, 3=XYA 3 distinct, or 5=XYZYA 4 distinct
            // Ignore for now that Mega 5X allows letters other than ABC
            //
            // Strategy for coping with non XYZABC order, and duplicates:
            // Iterate 1..axes.get()
            //   Obtain nth axis letter from order
            //   Test if this.capabilities.hasCapability(n_AXIS) already exists
            //   and addCapability() if not
            //
            // Capabilities now added for each distinct axis letter, potentially
            // different (fewer) than the number of physical axes Mega5X reports
            */
            char nthChar;
            for (int n=0; n < axes.get(); n++) {
                nthChar = order.get().charAt(n);
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
            // Record Axis ordering as a capability
            if (order.isPresent()) {
                AxisOrder = order.get();
                this.capabilities.addCapability(AXIS_ORDERING);
            } else {
                AxisOrder = "XYZABC";
            }
            // Log outcome, and to aid machine and setup diagnostics
            logger.log(Level.CONFIG, "Axes:{0} Order:{1}",
                new Object[] {axes.get(), order.get()}
            );
        }

        super.rawResponseHandler(response);
    }
    
    // No longer a listener event
    @Override
    public void handleStatusString(final String string) {
        if (this.capabilities == null) {
            return;
        }

        UGSEvent.ControlState before = getControlState();
        ControllerState beforeState = controllerStatus == null ? ControllerState.UNKNOWN : controllerStatus.getState();

        // Hook Mega5X specific utils
        controllerStatus = GrblMega5XUtils.getStatusFromStatusString(
                controllerStatus, string, capabilities, getFirmwareSettings().getReportingUnits(), AxisOrder);

        // Make UGS more responsive to the state being reported by GRBL.
        if (before != getControlState()) {
            this.dispatchStateChange(getControlState());
        }

        // GRBL 1.1 jog complete transition
        if (beforeState == ControllerState.JOG && controllerStatus.getState() == ControllerState.IDLE) {
            this.comm.cancelSend();
        }

        // Set and restore the step mode when transitioning from CHECK mode to IDLE.
        if (before == COMM_CHECK && getControlState() != COMM_CHECK) {
            setSingleStepMode(temporaryCheckSingleStepMode);
        } else if (before != COMM_CHECK && getControlState() == COMM_CHECK) {
            temporaryCheckSingleStepMode = getSingleStepMode();
            setSingleStepMode(true);
        }
        
        // Prior to GRBL v1.1 the GUI is required to keep checking locations
        // to verify that the machine has come to a complete stop after
        // pausing.
        if (isCanceling) {
            if (attemptsRemaining > 0 && lastLocation != null) {
                attemptsRemaining--;
                // If the machine goes into idle, we no longer need to cancel.
                if (controllerStatus.getState() == ControllerState.IDLE || controllerStatus.getState() == ControllerState.CHECK) {
                    isCanceling = false;

                    // Make sure the GUI gets updated
                    this.dispatchStateChange(getControlState());
                }
                // Otherwise check if the machine is Hold/Queue and stopped.
                else if (controllerStatus.getState() == ControllerState.HOLD && lastLocation.equals(this.controllerStatus.getMachineCoord())) {
                    try {
                        this.issueSoftReset();
                    } catch(Exception e) {
                        this.dispatchConsoleMessage(MessageType.ERROR, e.getMessage() + "\n");
                    }
                    isCanceling = false;
                }
                if (isCanceling && attemptsRemaining == 0) {
                    this.dispatchConsoleMessage(MessageType.ERROR, Localization.getString("grbl.exception.cancelReset") + "\n");
                }
            }
            lastLocation = new Position(this.controllerStatus.getMachineCoord());
        }
        
        dispatchStatusString(controllerStatus);
    }
}
