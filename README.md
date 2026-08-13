# ARBalls 2.0

Camera passthrough with 3D balls that obey gravity, land on a surface you set,
bounce off each other, and now bounce off real objects the app can pick out of
the picture.

## What changed since 1.0

**Stability.** Version 1 used the rotation sensor alone, so every small hand
movement moved the surface. Two fixes:

1. The field of view is no longer guessed. It is read from the lens data
   (`LENS_INFO_AVAILABLE_FOCAL_LENGTHS` / `SENSOR_INFO_PHYSICAL_SIZE`), so a
   ball three degrees off centre is drawn three degrees off centre. A wrong
   field of view is felt as the ball sliding whenever the phone turns.
2. Every camera frame is downsampled and matched against the previous frame to
   measure how far the picture actually moved. That is compared against how far
   the gyro says the virtual scene should have moved, and the difference is fed
   back as a slow correction to the world orientation. The content is now
   locked to the image rather than to a drifting gyro. The status line shows
   `lock locked` when a match is found and `lock weak` when the view is too
   plain or too blurry to match, which is normal on a blank wall.

The `Lock on/off` button turns that correction off, so both behaviours can be
compared directly.

**Objects.** The app learns the colour of the table (press `Scan` while
pointing at an empty part of it), then tests every 5 cm cell of the surface
against that colour. Cells that do not look like the table become 8 cm tall
blocks that the ball collides with: it bounces off the sides and can come to
rest on top. They are drawn as red squares so what the app detected is always
visible. `Obj: off / low / med / high` sets how different a cell has to look.

## What it still cannot do

- No depth sensing. Object detection is colour based, so an object the same
  colour as the table is invisible to it, and a strong shadow or a patterned
  tablecloth reads as an object. Detection also spills behind tall objects,
  because the app only knows the surface is hidden there, not why.
- No positional tracking. Turning the phone is handled; walking sideways is
  not, so the surface still slides if you move the phone bodily. Only ARCore
  would fix that properly.
- Real objects do not occlude the balls - a ball behind a mug still draws in
  front of it.

## Controls

- Tap empty space: drop a ball. Drag a ball and let go: throw it.
- Ball / Clear: add or remove balls.
- Center: bring the balls back in front and reset the tracking correction.
- Scan: relearn the table colour from the middle of the view.
- Lock on/off: image based stabilisation.
- Table - / +: move the landing surface in 5 cm steps.
- Obj: detection sensitivity, off to high.
- Map on/off: show or hide the green grid and the red object squares.
