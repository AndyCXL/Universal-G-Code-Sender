/*
    Copyright 2013-2020 Will Winder

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

import com.willwinder.universalgcodesender.firmware.IFirmwareSettings;
import com.willwinder.universalgcodesender.firmware.grbl.GrblFirmwareSettings;
import com.willwinder.universalgcodesender.gcode.GcodeCommandCreator;
import com.willwinder.universalgcodesender.gcode.util.GcodeUtils;
import com.willwinder.universalgcodesender.i18n.Localization;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.ControllerStatusBuilder;
import com.willwinder.universalgcodesender.listeners.MessageType;
import com.willwinder.universalgcodesender.model.*;
import com.willwinder.universalgcodesender.model.UGSEvent.ControlState;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import com.willwinder.universalgcodesender.types.GrblFeedbackMessage;
import com.willwinder.universalgcodesender.types.GrblSettingMessage;
import com.willwinder.universalgcodesender.utils.GrblLookups;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static com.willwinder.universalgcodesender.CapabilitiesConstants.*;

import static com.willwinder.universalgcodesender.model.UGSEvent.ControlState.COMM_CHECK;
import static com.willwinder.universalgcodesender.model.UGSEvent.ControlState.COMM_IDLE;

/**
 * GRBL Control layer, coordinates all aspects of control.
 *
 * @author wwinder
 */
public class GrblMega5XController extends AbstractController {
    private static final Logger logger = Logger.getLogger(GrblMega5XController.class.getName());

    private static final GrblLookups ALARMS = new GrblLookups("alarm_codes");
    private static final GrblLookups ERRORS = new GrblLookups("error_codes");
    private StatusPollTimer positionPollTimer;

    // Grbl state
    private double grblVersion = 0.0;           // The 0.8 in 'Grbl 0.8c'
    private Character grblVersionLetter = null; // The c in 'Grbl 0.8c'
    protected Boolean isReady = false;          // Not ready until version is received.
    private Capabilities capabilities = new Capabilities();
    private final GrblFirmwareSettings firmwareSettings;

    // Polling state
    public ControllerStatus controllerStatus = new ControllerStatus(ControllerState.DISCONNECTED, new Position(0,0,0,Units.MM), new Position(0,0,0,Units.MM));

    // Canceling state
    protected Boolean isCanceling = false;     // Set for the position polling thread.
    protected int attemptsRemaining;
    protected Position lastLocation;

    /**
     * For storing a temporary state if using single step mode when entering the state
     * check mode. When leaving check mode the temporary single step mode will be reverted.
     */
    public boolean temporaryCheckSingleStepMode = false;

    public GrblMega5XController(AbstractCommunicator comm) {
        super(comm);
        
        this.commandCreator = new GcodeCommandCreator();
        this.positionPollTimer = new StatusPollTimer(this);

        // Add our controller settings manager
        this.firmwareSettings = new GrblFirmwareSettings(this);
        this.comm.addListener(firmwareSettings);
    }
    
    public GrblMega5XController() {
        this(new GrblCommunicator());
    }

    // Axis order
    static String axisOrder = null;
    static Pattern axisCountPattern = Pattern.compile("\\[AXS:(\\d*):([XYZABC]*)]");
    
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
        return Optional.of(m.group(2).trim());
    }
    
    @Override
    public Boolean handlesAllStateChangeEvents() {
        return capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME);
    }

    @Override
    public Capabilities getCapabilities() {
        return capabilities;
    }

    @Override
    public IFirmwareSettings getFirmwareSettings() {
        return firmwareSettings;
    }

    /***********************
     * API Implementation. *
     ***********************/

    private static String lookupCode(String input, boolean shortString) {
        if (input.contains(":")) {
            String inputParts[] = input.split(":");
            if (inputParts.length == 2) {
                String code = inputParts[1].trim();
                if (StringUtils.isNumeric(code)) {
                    String[] lookupParts = null;
                    switch(inputParts[0].toLowerCase()) {
                        case "error":
                            lookupParts = ERRORS.lookup(code);
                            break;
                        case "alarm":
                            lookupParts = ALARMS.lookup(code);
                            break;
                        default:
                            return input;
                    }

                    if (lookupParts == null) {
                        return "(" + input + ") An unknown error has occurred";
                    } else if (shortString ) {
                        return input + " (" + lookupParts[1] + ")";
                    } else {
                        return "(" + input + ") " + lookupParts[2];
                    }
                }
            }
        }

        return input;
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
                axisOrder = order.get();
                this.capabilities.addCapability(AXIS_ORDERING);
            } else {
                axisOrder = "XYZABC";
            }
            // Log outcome, and to aid machine and setup diagnostics
            logger.log(Level.CONFIG, "Axes:{0} Order:{1}",
                new Object[] {axes.get(), order.get()}
            );
        }
        //
        //
        String processed = response;
        try {
            boolean verbose = false;

            if (GrblMega5XUtils.isOkResponse(response)) {
                this.commandComplete(processed);
            }

            // Error case.
            else if (GrblMega5XUtils.isOkErrorAlarmResponse(response)) {
                if (GrblMega5XUtils.isAlarmResponse(response)) {
                    //this is not updating the state to Alarm in the GUI, and the alarm is no longer being processed
                    controllerStatus = ControllerStatusBuilder
                            .newInstance(controllerStatus)
                            .setState(ControllerState.ALARM)
                            .build();

                    Alarm alarm = GrblMega5XUtils.parseAlarmResponse(response);
                    dispatchAlarm(alarm);
                    dispatchStatusString(controllerStatus);
                    dispatchStateChange(COMM_IDLE);
                }

                // If there is an active command, mark it as completed with error
                Optional<GcodeCommand> activeCommand = this.getActiveCommand();
                if( activeCommand.isPresent() ) {
                    processed =
                            String.format(Localization.getString("controller.exception.sendError"),
                                    activeCommand.get().getCommandString(),
                                    lookupCode(response, false)).replaceAll("\\.\\.", "\\.");
                    this.dispatchConsoleMessage(MessageType.ERROR, processed + "\n");
                    this.commandComplete(processed);
                } else {
                    processed =
                            String.format(Localization.getString("controller.exception.unexpectedError"),
                                    lookupCode(response, false)).replaceAll("\\.\\.", "\\.");
                    dispatchConsoleMessage(MessageType.INFO,processed + "\n");
                }
                checkStreamFinished();
                processed = "";
            }

            else if (GrblMega5XUtils.isGrblVersionString(response)) {
                this.isReady = true;
                resetBuffers();

                // When exiting COMM_CHECK mode a soft reset is done, do not clear the
                // controller status because we need to know the previous state for resetting
                // single step mode
                if (getControlState() != COMM_CHECK) {
                    this.controllerStatus = null;
                }

                positionPollTimer.stop();
                positionPollTimer.start();

                // In case a reset occurred while streaming.
                if (this.isStreaming()) {
                    checkStreamFinished();
                }
                
                this.grblVersion = GrblMega5XUtils.getVersionDouble(response);
                this.grblVersionLetter = GrblMega5XUtils.getVersionLetter(response);

                this.capabilities = GrblMega5XUtils.getGrblStatusCapabilities(this.grblVersion, this.grblVersionLetter);

                try {
                    this.sendCommandImmediately(createCommand(GrblMega5XUtils.GRBL_VIEW_SETTINGS_COMMAND));
                    this.sendCommandImmediately(createCommand(GrblMega5XUtils.GRBL_VIEW_PARSER_STATE_COMMAND));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                
                Logger.getLogger(GrblMega5XController.class.getName()).log(Level.CONFIG, 
                        "{0} = {1}{2}", new Object[]{Localization.getString("controller.log.version"), this.grblVersion, this.grblVersionLetter});
                Logger.getLogger(GrblMega5XController.class.getName()).log(Level.CONFIG, 
                        "{0} = {1}", new Object[]{Localization.getString("controller.log.realtime"), this.capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME)});
            }
            
            else if (GrblMega5XUtils.isGrblProbeMessage(response)) {
                Position p = GrblMega5XUtils.parseProbePosition(response, getFirmwareSettings().getReportingUnits(), axisOrder);
                if (p != null) {
                    dispatchProbeCoordinates(p);
                }
            }

            else if (GrblMega5XUtils.isGrblStatusString(response)) {
                // Only 1 poll is sent at a time so don't decrement, reset to zero.
                positionPollTimer.receivedStatus();

                // Status string goes to verbose console
                verbose = true;
                
                this.handleStatusString(response);
                this.checkStreamFinished();
            }

            else if (GrblMega5XUtils.isGrblFeedbackMessage(response, capabilities)) {
                GrblFeedbackMessage grblFeedbackMessage = new GrblFeedbackMessage(response);
                // Convert feedback message to raw commands to update modal state.
                this.updateParserModalState(new GcodeCommand(GrblMega5XUtils.parseFeedbackMessage(response, capabilities)));
                this.dispatchConsoleMessage(MessageType.VERBOSE, grblFeedbackMessage.toString() + "\n");
                setDistanceModeCode(grblFeedbackMessage.getDistanceMode());
                setUnitsCode(grblFeedbackMessage.getUnits());
                dispatchStateChange(COMM_IDLE);
            }

            else if (GrblMega5XUtils.isGrblSettingMessage(response)) {
                GrblSettingMessage message = new GrblSettingMessage(response);
                processed = message.toString();
            }

            if (StringUtils.isNotBlank(processed)) {
                if (verbose) {
                    this.dispatchConsoleMessage(MessageType.VERBOSE,processed + "\n");
                } else {
                    this.dispatchConsoleMessage(MessageType.INFO, processed + "\n");
                }
            }
        } catch (Exception e) {
            String message = "";
            if (e.getMessage() != null) {
                message = ": " + e.getMessage();
            }
            message = Localization.getString("controller.error.response")
                    + " <" + processed + ">" + message;

            logger.log(Level.SEVERE, message, e);
            this.dispatchConsoleMessage(MessageType.ERROR,message + "\n");
        }
    }

    @Override
    protected void pauseStreamingEvent() throws Exception {
        if (this.capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME)) {
            this.comm.sendByteImmediately(GrblMega5XUtils.GRBL_PAUSE_COMMAND);
        }
    }
    
    @Override
    protected void resumeStreamingEvent() throws Exception {
        if (this.capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME)) {
            this.comm.sendByteImmediately(GrblMega5XUtils.GRBL_RESUME_COMMAND);
        }
    }
    
    @Override
    protected void closeCommBeforeEvent() {
        positionPollTimer.stop();
    }
    
    @Override
    protected void closeCommAfterEvent() {
        this.grblVersion = 0.0;
        this.grblVersionLetter = null;
    }
    
    @Override
    protected void openCommAfterEvent() throws Exception {
        this.comm.sendByteImmediately(GrblMega5XUtils.GRBL_RESET_COMMAND);
    }

    @Override
    protected void isReadyToStreamCommandsEvent() throws Exception {
        isReadyToSendCommandsEvent();
        if (this.controllerStatus != null && this.controllerStatus.getState() == ControllerState.ALARM) {
            throw new Exception(Localization.getString("grbl.exception.Alarm"));
        }
    }

    @Override
    protected void isReadyToSendCommandsEvent() throws Exception {
        if (!this.isReady) {
            throw new Exception(Localization.getString("controller.exception.booting"));
        }
    }
    
    @Override
    protected void cancelSendBeforeEvent() throws Exception {
        boolean paused = isPaused();
        // The cancel button is left enabled at all times now, but can only be
        // used for some versions of GRBL.
        if (paused && !this.capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME)) {
            throw new Exception("Cannot cancel while paused with this version of GRBL. Reconnect to reset GRBL.");
        }

        // If we're canceling a "jog" just send the door hold command.
        if (capabilities.hasJogging() && controllerStatus != null &&
                controllerStatus.getState() == ControllerState.JOG) {
            this.comm.sendByteImmediately(GrblMega5XUtils.GRBL_JOG_CANCEL_COMMAND);
        }
        // Otherwise, check if we can get fancy with a soft reset.
        else if (!paused && this.capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME)) {
            try {
                this.pauseStreaming();
                this.dispatchStateChange(ControlState.COMM_SENDING_PAUSED);
            } catch (Exception e) {
                // Oh well, was worth a shot.
                System.out.println("Exception while trying to issue a soft reset: " + e.getMessage());
            }
        }
    }
    
    @Override
    protected void cancelSendAfterEvent() {
        if (this.capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME) && this.getStatusUpdatesEnabled()) {
            // Trigger the position listener to watch for the machine to stop.
            this.attemptsRemaining = 50;
            this.isCanceling = true;
            this.lastLocation = null;
        }
    }

    @Override
    protected Boolean isIdleEvent() {
        if (this.capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME)) {
            return getControlState() == COMM_IDLE || getControlState() == COMM_CHECK;
        }
        // Otherwise let the abstract controller decide.
        return true;
    }

    @Override
    public ControlState getControlState() {
        if (!this.capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME)) {
            return super.getControlState();
        }

        ControllerState state = this.controllerStatus == null ? ControllerState.UNKNOWN : this.controllerStatus.getState();
        switch(state) {
            case JOG:
            case RUN:
                return ControlState.COMM_SENDING;
            case HOLD:
            case DOOR:
                return ControlState.COMM_SENDING_PAUSED;
            case IDLE:
                if (isStreaming()){
                    return ControlState.COMM_SENDING_PAUSED;
                } else {
                    return ControlState.COMM_IDLE;
                }
            case ALARM:
                return ControlState.COMM_IDLE;
            case CHECK:
                if (isStreaming() && comm.isPaused()) {
                    return ControlState.COMM_SENDING_PAUSED;
                } else if (isStreaming() && !comm.isPaused()) {
                    return ControlState.COMM_SENDING;
                } else {
                    return COMM_CHECK;
                }
            default:
                return ControlState.COMM_IDLE;
        }
    }

    /**
     * Sends the version specific homing cycle to the machine.
     */
    @Override
    public void performHomingCycle() throws Exception {
        if (this.isCommOpen()) {
            String gcode = GrblMega5XUtils.getHomingCommand(this.grblVersion, this.grblVersionLetter);
            if (!"".equals(gcode)) {
                GcodeCommand command = createCommand(gcode);
                sendCommandImmediately(command);
                controllerStatus = ControllerStatusBuilder
                        .newInstance(controllerStatus)
                        .setState(ControllerState.HOME)
                        .build();
                dispatchStatusString(controllerStatus);
                return;
            }
        }
        // Throw exception
        super.performHomingCycle();
    }
    
    @Override
    public void resetCoordinatesToZero() throws Exception {
        if (this.isCommOpen()) {
            String gcode = GrblMega5XUtils.getResetCoordsToZeroCommand(this.grblVersion, this.grblVersionLetter);
            if (!"".equals(gcode)) {
                GcodeCommand command = createCommand(gcode);
                this.sendCommandImmediately(command);
                return;
            }
        }
        // Throw exception
        super.resetCoordinatesToZero();
    }
    
    @Override
    public void resetCoordinateToZero(final Axis axis) throws Exception {
        if (this.isCommOpen()) {
            String gcode = GrblMega5XUtils.getResetCoordToZeroCommand(axis, getCurrentGcodeState().getUnits(), this.grblVersion, this.grblVersionLetter);
            if (!"".equals(gcode)) {
                GcodeCommand command = createCommand(gcode);
                this.sendCommandImmediately(command);
                return;
            }
        }
        // Throw exception
        super.resetCoordinatesToZero();
    }

    @Override
    public void setWorkPosition(PartialPosition axisPosition) throws Exception {
        if (!this.isCommOpen()) {
            throw new Exception("Must be connected to set work position");
        }

        Units currentUnits = getCurrentGcodeState().getUnits();
        PartialPosition position = axisPosition.getPositionIn(currentUnits);
        String gcode = GrblMega5XUtils.getSetCoordCommand(position, this.grblVersion, this.grblVersionLetter);
        if (StringUtils.isNotEmpty(gcode)) {
            GcodeCommand command = createCommand(gcode);
            this.sendCommandImmediately(command);
        }
    }
    
    @Override
    public void killAlarmLock() throws Exception {
        if (this.isCommOpen()) {
            String gcode = GrblMega5XUtils.getKillAlarmLockCommand(this.grblVersion, this.grblVersionLetter);
            if (!"".equals(gcode)) {
                GcodeCommand command = createCommand(gcode);
                this.sendCommandImmediately(command);
                return;
            }
        }
        // Throw exception
        super.killAlarmLock();
    }

    @Override
    public void openDoor() throws Exception {
        if (!this.isCommOpen()) {
            throw new RuntimeException("Not connected to the controller");
        }

        comm.sendByteImmediately(GrblMega5XUtils.GRBL_DOOR_COMMAND);
    }

    @Override
    public void toggleCheckMode() throws Exception {
        if (this.isCommOpen()) {
            String gcode = GrblMega5XUtils.getToggleCheckModeCommand(this.grblVersion, this.grblVersionLetter);
            if (!"".equals(gcode)) {
                GcodeCommand command = createCommand(gcode);
                this.sendCommandImmediately(command);
                return;
            }
        }
        // Throw exception
        super.toggleCheckMode();
    }

    @Override
    public void viewParserState() throws Exception {
        if (this.isCommOpen()) {
            String gcode = GrblMega5XUtils.getViewParserStateCommand(this.grblVersion, this.grblVersionLetter);
            if (!"".equals(gcode)) {
                GcodeCommand command = createCommand(gcode);
                this.sendCommandImmediately(command);
                return;
            }
        }
        // Throw exception
        super.viewParserState();
    }

    @Override
    public void requestStatusReport() throws Exception {
        if (!this.isCommOpen()) {
            throw new RuntimeException("Not connected to the controller");
        }

        comm.sendByteImmediately(GrblMega5XUtils.GRBL_STATUS_COMMAND);
    }

    /**
     * If it is supported, a soft reset real-time command will be issued.
     */
    @Override
    public void softReset() throws Exception {
        if (this.isCommOpen() && this.capabilities.hasCapability(GrblCapabilitiesConstants.REAL_TIME)) {
            this.comm.sendByteImmediately(GrblMega5XUtils.GRBL_RESET_COMMAND);
            //Does GRBL need more time to handle the reset?
            this.comm.cancelSend();
        }
    }

    @Override
    public void jogMachine(PartialPosition distance, double feedRate) throws Exception {
        if (capabilities.hasCapability(GrblCapabilitiesConstants.HARDWARE_JOGGING)) {
            String commandString = GcodeUtils.generateMoveCommand( "G91", feedRate, distance);
            GcodeCommand command = createCommand("$J=" + commandString);
            sendCommandImmediately(command);
        } else {
            super.jogMachine(distance, feedRate);
        }
    }

    @Override
    public void jogMachineTo(PartialPosition position, double feedRate) throws Exception {
        if (capabilities.hasCapability(GrblCapabilitiesConstants.HARDWARE_JOGGING)) {
            String commandString = GcodeUtils.generateMoveToCommand("G90", position, feedRate);
            GcodeCommand command = createCommand("$J=" + commandString);
            sendCommandImmediately(command);
        } else {
            super.jogMachineTo(position, feedRate);
        }
    }
    
    /************
     * Helpers.
     ************/

    public String getGrblVersion() {
        if (this.isCommOpen()) {
            StringBuilder str = new StringBuilder();
            str.append("Grbl ");
            if (this.grblVersion > 0.0) {
                str.append(this.grblVersion);
            }
            if (this.grblVersionLetter != null) {
                str.append(this.grblVersionLetter);
            }
            
            if (this.grblVersion <= 0.0 && this.grblVersionLetter == null) {
                str.append("<").append(Localization.getString("unknown")).append(">");
            }
            
            return str.toString();
        }
        return "<" + Localization.getString("controller.log.notconnected") + ">";
    }

    @Override
    public String getFirmwareVersion() {
        return getGrblVersion();
    }

    @Override
    public ControllerStatus getControllerStatus() {
        return controllerStatus;
    }

    // No longer a listener event
    //private void handleStatusString(final String string) {
    public void handleStatusString(final String string) {
        if (this.capabilities == null) {
            return;
        }

        ControlState before = getControlState();
        ControllerState beforeState = controllerStatus == null ? ControllerState.UNKNOWN : controllerStatus.getState();

        controllerStatus = GrblMega5XUtils.getStatusFromStatusString(
                controllerStatus, string, capabilities, getFirmwareSettings().getReportingUnits(), axisOrder);

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
    
    @Override
    protected void statusUpdatesEnabledValueChanged() {
        if (getStatusUpdatesEnabled()) {
            positionPollTimer.stop();
            positionPollTimer.start();
        } else {
            positionPollTimer.stop();
        }
    }
    
    @Override
    protected void statusUpdatesRateValueChanged() {
        positionPollTimer.stop();
        positionPollTimer.start();
    }

    @Override
    public void sendOverrideCommand(Overrides command) throws Exception {
        Byte realTimeCommand = GrblMega5XUtils.getOverrideForEnum(command, capabilities);
        if (realTimeCommand != null) {
            this.dispatchConsoleMessage(MessageType.INFO, String.format(">>> 0x%02x\n", realTimeCommand));
            this.comm.sendByteImmediately(realTimeCommand);
        }
    }
}
