package {
import flash.display.Sprite;

import flash.geom.Matrix;
import flash.geom.Point;

import flash.events.Event;

import mx.core.MovieClipAsset;

public class Kart extends Sprite
{
    public function Kart (camera :Camera)
    {
        _camera = camera;
        _kart = new BOWSER();
        _kart.gotoAndStop(1);
        addChild(_kart);

        addEventListener(Event.ENTER_FRAME, enterFrame);
    }

    public function moveForward (moving :Boolean) :void 
    { 
        keyAction(moving, MOVEMENT_FORWARD);
    }

    public function moveBackward (moving :Boolean) :void
    {
        keyAction(moving, MOVEMENT_BACKWARD);
    }

    public function turnLeft (turning :Boolean) :void
    {
        keyAction(turning, MOVEMENT_LEFT);
    }

    public function turnRight (turning :Boolean) :void
    {
        keyAction(turning, MOVEMENT_RIGHT);
    }

    public function enterFrame (event :Event) :void
    {
        // TODO: base these speeds on something fairer than enterFrame.  Using this method,
        // the person with the fastest computer (higher framerate) gets to drive more quickly.
        // rotate camera
        if (_movement & (MOVEMENT_RIGHT | MOVEMENT_LEFT)) {
            _currentAngle = Math.min(MAX_TURN_ANGLE, _currentAngle + TURN_ACCELERATION);

            if (_currentSpeed != 0) {
                if (_movement & MOVEMENT_RIGHT) {
                    _camera.angle += _currentAngle
                } else if (_movement & MOVEMENT_LEFT) {
                    _camera.angle -= _currentAngle
                }
            }

            var frame :int = Math.ceil((_currentAngle / MAX_TURN_ANGLE) * FRAMES_PER_TURN) - 1;
            if (_movement & MOVEMENT_RIGHT) {
                frame+= RIGHT_TURN_FRAME_OFFSET;
            } else {
                frame+= LEFT_TURN_FRAME_OFFSET;
            }
            if (_kart.currentFrame != frame) {
                _kart.gotoAndStop(frame);
            }
        } else {
            _currentAngle = 0;
            if (_kart.currentFrame != 1) {
                _kart.gotoAndStop(1);
            }
        }

        var rotation :Matrix;
        if (_movement & MOVEMENT_FORWARD) {
            if (_currentSpeed >= 0) {
                _currentSpeed = (_currentSpeed >= SPEED_MAX) ? SPEED_MAX : 
                    _currentSpeed + ACCELERATION_GAS;
            } else { 
                _currentSpeed += ACCELERATION_BRAKE;
            }
        } else if (_movement & MOVEMENT_BACKWARD) {
            if (_currentSpeed <= 0) {
                _currentSpeed = (_currentSpeed <= SPEED_MIN) ? SPEED_MIN : 
                    _currentSpeed - ACCELERATION_GAS;
            } else {
                _currentSpeed -= ACCELERATION_BRAKE;
            }
        } else {
            if ((_currentSpeed > ACCELERATION_COAST && _currentSpeed > 0) || 
                (_currentSpeed < ACCELERATION_COAST && _currentSpeed < 0)) {
                if (_currentSpeed > 0) {
                    _currentSpeed -= ACCELERATION_COAST;
                } else {
                    _currentSpeed += ACCELERATION_COAST;
                }
            } else {
                _currentSpeed = 0;
            }
        }
        rotation = new Matrix();
        rotation.rotate(_camera.angle);
        _camera.position = _camera.position.add(rotation.transformPoint(new Point(0, 
            -_currentSpeed)));
    }

    protected function keyAction (inMotion :Boolean, flag :int) :void
    {
        if (inMotion) {
            _movement |= flag;
        } else {
            _movement &= ~flag;
        }
    }

    /** Bit flags to indicate which movement keys are pressed */
    protected var _movement :int = 0;

    /** reference to ground object */
    protected var _camera :Camera;

    /** Embedded cart movie clip */
    protected var _kart :MovieClipAsset;

    /** Kart's current speed */
    protected var _currentSpeed :Number = 0;

    /** Kart's current turn angle */
    protected var _currentAngle :Number = 0;

    /** Bowser Kart */
    [Embed(source='rsrc/bowser.swf#kart')]
    protected static const BOWSER :Class;

    /** The number of movie clip frames used for one of the turn directions */
    protected static const FRAMES_PER_TURN :int = 3;

    /** The offset into the movie clip for the frames for both turn types. */
    protected static const RIGHT_TURN_FRAME_OFFSET :int = 2;
    protected static const LEFT_TURN_FRAME_OFFSET :int = 5;

    /** constants to control kart motion properties */
    protected static const SPEED_MAX :int = 30;
    protected static const SPEED_MIN :int = -5;
    protected static const ACCELERATION_GAS :Number = 0.5;
    protected static const ACCELERATION_BRAKE :Number = 1.5;
    protected static const ACCELERATION_COAST :Number = 0.5;
    protected static const MAX_TURN_ANGLE :Number = 0.0524; // 3 degrees
    protected static const TURN_ACCELERATION :Number = 0.01;

    /** flags for the _movement bit flag variable */
    protected static const MOVEMENT_FORWARD :int = 0x01;
    protected static const MOVEMENT_BACKWARD :int = 0x02;
    protected static const MOVEMENT_LEFT :int = 0x04;
    protected static const MOVEMENT_RIGHT :int = 0x08;
}
}
