/*
    Copyright 2012-2020 Will Winder

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

import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.ControllerStatus;
import com.willwinder.universalgcodesender.listeners.ControllerStatus.AccessoryStates;
import com.willwinder.universalgcodesender.listeners.ControllerStatus.EnabledPins;
import com.willwinder.universalgcodesender.listeners.ControllerStatus.OverridePercents;
import com.willwinder.universalgcodesender.model.*;
import com.willwinder.universalgcodesender.model.UnitUtils.Units;
import com.willwinder.universalgcodesender.types.GcodeCommand;
import org.apache.commons.lang3.StringUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collection of useful Grbl related utilities.
 *
 * @author wwinder
 */
public class GrblMega5XUtils extends GrblUtils {
    
    /**
     * Parses a GRBL status string in v1.x format:
     * 1.x: <status|WPos:1,2,3|Bf:0,0|WCO:0,0,0>
     * @param lastStatus required for the 1.x version which requires WCO coords
     *                   and override status from previous status updates.
     * @param status the raw status string
     * @param version capabilities flags
     * @param reportingUnits units
     * @return the parsed controller status
     */
    static protected ControllerStatus getStatusFromStatusString(
            ControllerStatus lastStatus, final String status,
            final Capabilities version, Units reportingUnits, String axisOrder) {
        return getStatusFromStatusStringV1(lastStatus, status, reportingUnits, axisOrder);
    }

    /**
     * Parses a GRBL status string in in the v1.x format:
     * 1.x: <status|WPos:1,2,3|Bf:0,0|WCO:0,0,0>
     * @param lastStatus required for the 1.x version which requires WCO coords
     *                   and override status from previous status updates.
     * @param status the raw status string
     * @param reportingUnits units
     * @return the parsed controller status
     */
    public static ControllerStatus getStatusFromStatusStringV1(ControllerStatus lastStatus, String status, Units reportingUnits, String axisOrder) {
        String stateString = "";
        Position MPos = null;
        Position WPos = null;
        Position WCO = null;

        OverridePercents overrides = null;
        EnabledPins pins = null;
        AccessoryStates accessoryStates = null;

        double feedSpeed = 0;
        double spindleSpeed = 0;
        if(lastStatus != null) {
            feedSpeed = lastStatus.getFeedSpeed();
            spindleSpeed = lastStatus.getSpindleSpeed();
        }
        boolean isOverrideReport = false;

        // Parse out the status messages.
        for (String part : status.substring(0, status.length()-1).split("\\|")) {
            if (part.startsWith("<")) {
                int idx = part.indexOf(':');
                if (idx == -1)
                    stateString = part.substring(1);
                else
                    stateString = part.substring(1, idx);
            }
            else if (part.startsWith("MPos:")) {
                MPos = GrblMega5XUtils.getPositionFromStatusString(status, machinePattern, reportingUnits, axisOrder);
            }
            else if (part.startsWith("WPos:")) {
                WPos = GrblMega5XUtils.getPositionFromStatusString(status, workPattern, reportingUnits, axisOrder);
            }
            else if (part.startsWith("WCO:")) {
                WCO = GrblMega5XUtils.getPositionFromStatusString(status, wcoPattern, reportingUnits, axisOrder);
            }
            else if (part.startsWith("Ov:")) {
                isOverrideReport = true;
                String[] overrideParts = part.substring(3).trim().split(",");
                if (overrideParts.length == 3) {
                    overrides = new OverridePercents(
                            Integer.parseInt(overrideParts[0]),
                            Integer.parseInt(overrideParts[1]),
                            Integer.parseInt(overrideParts[2]));
                }
            }
            else if (part.startsWith("F:")) {
                feedSpeed = parseFeedSpeed(part);
            }
            else if (part.startsWith("FS:")) {
                String[] parts = part.substring(3).split(",");
                feedSpeed = Double.parseDouble(parts[0]);
                spindleSpeed = Double.parseDouble(parts[1]);
            }
            else if (part.startsWith("Pn:")) {
                String value = part.substring(part.indexOf(':')+1);
                pins = new EnabledPins(value);
            }
            else if (part.startsWith("A:")) {
                String value = part.substring(part.indexOf(':')+1);
                accessoryStates = new AccessoryStates(value);
            }
        }

        // Grab WCO from state information if necessary.
        if (WCO == null) {
            // Grab the work coordinate offset.
            if (lastStatus != null && lastStatus.getWorkCoordinateOffset() != null) {
                WCO = lastStatus.getWorkCoordinateOffset();
            } else {
                WCO = new Position(0,0,0,0,0,0, reportingUnits);
            }
        }

        // Calculate missing coordinate with WCO
        if (WPos == null && MPos != null) {
            WPos = new Position(MPos.x-WCO.x, MPos.y-WCO.y, MPos.z-WCO.z, MPos.a-WCO.a, MPos.b-WCO.b, MPos.c-WCO.c, reportingUnits);
        } else if (MPos == null && WPos != null) {
            MPos = new Position(WPos.x+WCO.x, WPos.y+WCO.y, WPos.z+WCO.z, WPos.a+WCO.a, WPos.b+WCO.b, WPos.c+WCO.c, reportingUnits);
        }

        if (!isOverrideReport && lastStatus != null) {
            overrides = lastStatus.getOverrides();
            pins = lastStatus.getEnabledPins();
            accessoryStates = lastStatus.getAccessoryStates();
        }
        else if (isOverrideReport) {
            // If this is an override report and the 'Pn:' field wasn't sent
            // set all pins to a disabled state.
            if (pins == null) {
                pins = new EnabledPins("");
            }
            // Likewise for accessory states.
            if (accessoryStates == null) {
                accessoryStates = new AccessoryStates("");
            }
        }

        ControllerState state = getControllerStateFromStateString(stateString);
        return new ControllerStatus(state, MPos, WPos, feedSpeed, reportingUnits, spindleSpeed, overrides, WCO, pins, accessoryStates);
    }
    
    static private Position getPositionFromStatusString(final String status, final Pattern pattern, Units reportingUnits, String axisOrder) {
        /* GrblMega5XController knows the reported Axis order, eg: XYZ or XYA or XYZYA
        // and added capabilities according to each unique axis letter, even if the count
        // of axes was greater (XYZYA is 4 distinct letters 5 positions). Position reporting
        // is per physical axis, so XYZYA is the string order even though Y is duplicated
        // All other controllers it appears are XYZABC order for the reported axis count
        //
        // AbstractController and overrides now has String getAxisLetterOrder() which returns
        // the controller's obtained (eg: XYZYA) or presumed default ordering (XYZABC)
        */
        Matcher matcher = pattern.matcher(status);
      
        // Do we have any matches, if so assign them to axes
        if (matcher.find()) {
            // Is Axis ordering default, or dictated by the controller
            // String axisOrder defines the order
            Position result = new Position(Double.parseDouble(matcher.group(1)),
                    Double.parseDouble(matcher.group(2)),
                    Double.parseDouble(matcher.group(3)),
                    reportingUnits);

            char nthOrderChar;
            for (int n=4; n<= axisOrder.length(); n++) {
                nthOrderChar = axisOrder.charAt(n);
                switch (n) {
                    case 4: switch (nthOrderChar) {
                        case 'X': result.x = Double.parseDouble(matcher.group(n));
                        case 'Y': result.y = Double.parseDouble(matcher.group(n));
                        case 'Z': result.z = Double.parseDouble(matcher.group(n));
                        case 'A': result.a = Double.parseDouble(matcher.group(n));
                        case 'B': result.b = Double.parseDouble(matcher.group(n));
                        case 'C': result.c = Double.parseDouble(matcher.group(n));
                        }
                    break;
                    case 5: switch (nthOrderChar) {
                        case 'X': result.x = Double.parseDouble(matcher.group(n));
                        case 'Y': result.y = Double.parseDouble(matcher.group(n));
                        case 'Z': result.z = Double.parseDouble(matcher.group(n));
                        case 'A': result.a = Double.parseDouble(matcher.group(n));
                        case 'B': result.b = Double.parseDouble(matcher.group(n));
                        case 'C': result.c = Double.parseDouble(matcher.group(n));
                        }
                    break;
                    case 6: switch (nthOrderChar) {
                        case 'X': result.x = Double.parseDouble(matcher.group(n));
                        case 'Y': result.y = Double.parseDouble(matcher.group(n));
                        case 'Z': result.z = Double.parseDouble(matcher.group(n));
                        case 'A': result.a = Double.parseDouble(matcher.group(n));
                        case 'B': result.b = Double.parseDouble(matcher.group(n));
                        case 'C': result.c = Double.parseDouble(matcher.group(n));
                        }
                    break;
                    }
                }
            return result;
        }
        // No match, default return null
        return null;
    }
    
    /**
     * Parses the feed speed from a status string starting with "F:".
     * The supported formats are F:1000.0 or F:3000.0,100.0,100.0 which are current feed rate, requested feed rate and override feed rate
     *
     * @param part the part to parse
     * @return the parsed feed speed
     */
    private static double parseFeedSpeed(String part) {
        if(!part.startsWith("F:")) {
            return Double.NaN;
        }

        double feedSpeed;
        String[] feedStrings = StringUtils.split(part.substring(2), ",");
        if (feedStrings.length > 1) {
            if (feedStrings.length >= 3) {
                feedSpeed = Double.parseDouble(StringUtils.split(feedStrings[0], ",")[0]);
            } else {
                feedSpeed = 0;
            }
        } else {
            feedSpeed = Double.parseDouble(part.substring(2));
        }
        return feedSpeed;
    }
}
