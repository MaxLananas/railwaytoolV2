# Railway Tools For Axioms

A professional-grade Axiom addon designed for BuildTheEarth projects, providing advanced railway path generation with smooth curves and intelligent block placement.

## Overview

Railway Tools For Axioms extends Axiom's toolset with a specialized rail path creation tool. This mod enables builders to quickly generate realistic railway corridors by placing control points and letting the tool generate smooth, natural-looking paths with appropriate support structures.

## Features

### Intelligent Path Generation

The tool uses Catmull-Rom spline interpolation to create smooth, natural curves between your control points. This mathematical approach ensures that railway paths look organic and realistic, avoiding harsh angles and unnatural transitions.

### Adaptive Block Placement

The generator automatically selects appropriate blocks based on the path geometry:

- **Center markers**: Dead fire coral wall fans oriented along the path tangent
- **Straight sections**: Mud brick walls aligned with the direction of travel
- **Diagonal segments**: Corner wall configurations that smoothly connect perpendicular sections
- **Gentle curves**: Spruce shelves (or trapdoors on older versions) oriented outward from the curve

### Configurable Parameters

#### Density Control
Adjust the sampling density from 2 to 32 points per block. Higher values produce smoother curves at the cost of more computation.

#### Ground Snapping
Enable automatic terrain following to have your railway path conform to the existing landscape. The tool searches vertically to find the nearest solid surface.

#### Live Preview
Toggle real-time visualization of the generated path before committing changes. The preview uses animated transparency to clearly show the planned placement.

## Usage

### Basic Workflow

1. Select the BTE Rail Path tool from Axiom's tool palette (icon: `\ue912`)
2. Right-click to place control points defining your desired path
3. Observe the preview rendering between points
4. Press Enter to confirm and place blocks, or Delete to remove the last point
5. Press Escape to cancel and start over

### Controls

| Action | Function |
|--------|----------|
| Right-click | Add control point at target position |
| Enter | Confirm and place the railway path |
| Delete | Remove the last placed control point |
| Escape | Clear all points and reset the tool |

### Tool Settings

Access the configuration panel in Axiom's tool settings:

- **Density**: Controls curve smoothness (2-32 points/block)
- **Snap to Ground**: Automatically adjust Y-coordinates to terrain
- **Preview**: Toggle preview rendering

## Technical Details

### Dependencies

- **Minecraft**: 1.21.10
- **Fabric Loader**: ≥0.16.0
- **Fabric API**: Required
- **Axiom**: Required (not bundled)

### Block Palette

The tool uses the following blocks:
- `minecraft:dead_fire_coral_wall_fan` - Path centerline markers
- `minecraft:mud_brick_wall` - Side supports for straight and diagonal sections
- `minecraft:spruce_shelf` or `minecraft:spruce_trapdoor` - Curve indicators

### Algorithm

The path generation employs a multi-stage process:

1. **Spline interpolation**: Catmull-Rom curves generate smooth paths through control points
2. **Discretization**: Continuous curves are sampled to block-aligned positions
3. **Segment classification**: Each block is analyzed for movement type (straight, diagonal, or turning)
4. **Tangent calculation**: Local path direction determines block orientation
5. **Lateral placement**: Side blocks are positioned perpendicular to the path with type-appropriate states

## BuildTheEarth Integration

This tool was specifically developed for BuildTheEarth builders working on railway infrastructure. The block choices and curve parameters are optimized for creating survey markers and structural guides at 1:1 scale, particularly useful for:

- Railway right-of-way planning
- Track centerline marking
- Curve layout verification
- Infrastructure corridor establishment

## Permissions

Requires the following Axiom permissions:
- `TOOL` - Access to custom tools
- `BUILD_SECTION` - Block placement capability

## License

MIT License - See LICENSE file for details

## Support

For issues, suggestions, or contributions, please visit the project repository.

---

**Note**: This mod is a client-side addon and requires Axiom to function. It will not load if Axiom is not present in your mod directory.
