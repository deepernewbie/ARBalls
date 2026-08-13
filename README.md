# ARBalls 3.0

Camera passthrough with 3D balls that obey gravity, land on a real surface,
bounce off each other and off objects the app picks out of the picture.

## Registering the surface with markers

Put two small, distinctive objects on the table - bottle caps, coins, two
crosses drawn on paper, anything with a clear pattern about 3-5 cm across.
Press **Mark**, tap the first one, tap the second one.

From then on the app solves, every frame:

- the direction the phone is facing (heading), which the gyro alone loses,
- where the phone is relative to the markers, so leaning in and out and moving
  sideways all work,
- where the surface plane sits.

The one thing a single camera can never recover is scale, so the distance
between the markers is a number you set rather than measure. **Scale - / +**
stretches or shrinks the whole room. Adjust until the ball looks the size of a
real ball; the status line shows the implied marker gap and how high the phone
is above the table, which is an easy sanity check - if it says the phone is
40 cm up and that looks about right, the scale is right.

Green rings are the tracked markers. Yellow crosses are where the registered
surface thinks those markers are. When the two sit on top of each other,
tracking is good. A red ring means that marker was lost - it will pick it up
again when it comes back into view, or press Mark to redo it.

With only one marker visible the app keeps orientation locked but cannot see
position. With none it falls back to whole image matching, as in version 2.

**Reset** clears the markers and goes back to the plain, unregistered mode with
the surface a fixed distance below the phone, adjustable with Table - / +.

## Ball weight

**Ball: light / normal / heavy** sets what the next ball you drop is made of:

| | radius | mass | bounce | drag | rolling |
|---|---|---|---|---|---|
| light | 2.0 cm | 3 g | 0.82 | high | high |
| normal | 3.5 cm | 60 g | 0.60 | medium | medium |
| heavy | 3.0 cm | 900 g | 0.24 | low | low |

Existing balls keep the material they were dropped with, so you can mix them
and watch a heavy one plough through the light ones. Collisions use real
masses. Light balls are drawn pale, heavy ones steel grey.

## Objects

Press **Scan** pointing at an empty part of the table to learn its colour. Every
5 cm cell of the surface is then tested against that colour; cells that do not
match become 8 cm blocks the ball collides with, drawn as red squares.
**Obj:** sets how different a cell has to look. Detection is colour based, so
same coloured objects are invisible to it and hard shadows read as objects.

## Controls

- Tap the table: drop a ball where you tapped. Drag a ball and let go: throw it.
- Ball / Clear / Center: add, remove, or bring the balls back in front.
- Scan: relearn the table colour. Mark: register against two markers.
- Scale - / +: world scale. Table - / +: surface height when unregistered.
- Lock: whole image stabilisation on or off. Map: show the grid and squares.
- Reset: forget markers, tracking and objects.

## Still not possible

Real objects do not hide the balls - a ball behind a mug still draws in front
of it. Object detection has no depth, so it cannot tell how tall something is;
every detected object is treated as 8 cm high.
