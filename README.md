# ARBalls

Camera passthrough with 3D balls that obey real gravity, land on a surface you
set, bounce, roll and knock into each other. You can grab a ball with your
finger and throw it.

## How it works

- The rear camera fills the screen (CameraX preview).
- The phone's rotation sensor gives a 3-DoF orientation, so the balls stay put
  in the room while you pan the camera across them.
- Physics runs in a room-fixed frame: z is up, gravity is 9.81 m/s^2 down, and
  a horizontal plane (the "table") sits a set distance below the phone.
- Balls are drawn with true perspective projection: 3D position, 3D collisions,
  distance-correct size, and a contact shadow projected onto the table plane.

## What it does not do

There is no depth sensing and no plane detection, so the app cannot see your
actual table. You tell it where the surface is with the Table -/+ buttons, and
the faint green grid shows where the balls will land. There is also no
positional tracking: turning the phone works, walking around does not.

## Controls

- Tap empty space: drop a ball there
- Drag a ball, let go: throw it
- Ball: add one in front of you
- Center: bring all balls back in front of the camera
- Table - / Table +: move the landing surface down / up in 5 cm steps
- Clear: remove all balls
