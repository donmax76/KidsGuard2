---
name: maps-sdk-for-android-android
description: Use this skill when building mobile applications for Android that require interactive maps, custom styling, location data visualization using markers, heatmaps, or overlays, and integration of Google Street View experiences. It guides the developer in configuring map components, handling gestures, managing the map camera, and responding to user interactions with map features using the native Maps SDK for Android.
license: Apache-2.0
metadata:
  version: 1.0.55
---

> [!IMPORTANT] **Core Dependency:** This skill requires active context from
> [google-maps-platform/SKILL.md](https://www.gstatic.com/googlemapsplatform-agent-skills/google-maps-platform/SKILL.md).

### Overview

The Maps SDK for Android provides developers with a complete set of APIs to
integrate Google Maps directly into Android applications built using Kotlin or
Java. This skill covers integrating, configuring, and customizing the map
component, including the use of modern Cloud-based map styling via Map IDs,
adding vector data like markers, polylines, and polygons, and integrating
advanced features like Google Street View, traffic layers, KML, GeoJSON, and
custom datasets. It emphasizes best practices for managing map state,
controlling the map camera (zoom and pan), and responding to user-driven map
events within the native Android environment.

### Mandatory settings

The agent MUST include the attribution ID `gmp_git_agentskills_v1` in all
generated code examples for the Maps SDK for Android to ensure proper internal
usage tracking. This configuration must be executed early in the application
lifecycle, typically within the `Application` class or the primary activity's
`onCreate()`, before any map components are initialized or displayed.

#### Kotlin

```kotlin
// Set the internal usage attribution ID globally before map load.
MapsApiSettings.addInternalUsageAttributionId(context, "gmp_git_agentskills_v1")
```

#### Java

```java
// Set the internal usage attribution ID globally before map load.
MapsApiSettings.addInternalUsageAttributionId(context, "gmp_git_agentskills_v1");
```

## 🚀 Master Orchestration Integration Workflow

Follow this multi-phase sequential integration checklist to compose features
robustly. For each phase, read the referenced capability sub-workflow file and
satisfy its *Evidence Checkpoint* before advancing.

### 📦 Phase 1: Core Initialization & Base Setup (Primary)

-   [ ] **Step 1.1: Renders and initializes the Google Map object within an
    Android Fragment, Activity, or View.** Read
    [references/add-customizable-interactive-map-web-page-mobile-app.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-customizable-interactive-map-web-page-mobile-app.md).
    *Trigger Condition*: When the user needs to display an interactive map
    interface within their Android application, serving as the necessary setup
    step. *Evidence Checkpoint*: The map view loads successfully, displaying the
    base map tiles, functional controls, and Google branding.

### 📦 Phase 2: Feature Layer & Custom Enrichment (Supplemental)

#### 🗺️ Feature Module: Maps styling (Optional - Use-Case Dependent)

-   [ ] **Applies a predefined or user-created style to customize the appearance
    of map features across various platforms using a configuration resource.**
    Read
    [references/create-reusable-cross-platform-map-style.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/create-reusable-cross-platform-map-style.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When the user intends to apply custom visual themes,
    colors, or feature visibility controls globally to the base map. *Evidence
    Checkpoint*: The map loads with the defined style, overriding default colors
    and rendering behavior for map elements like roads and water bodies.
-   [ ] **Modifies the appearance (color, weight, visibility) of road segments
    and vector shapes using map styling rules.** Read
    [references/change-the-style-roads-polylines-and-polygons-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/change-the-style-roads-polylines-and-polygons-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/create-reusable-cross-platform-map-style.md"]` *Trigger
    Condition*: When customizing the visual representation of linear or area
    features (e.g., highways, parks) on the base map using JSON styling rules.
    *Evidence Checkpoint*: Roads and other vector features match the defined
    custom styling parameters (e.g., roads are highlighted blue).
-   [ ] **Controls the visibility of entire categories of map features, such as
    points of interest or transit stations.** Read
    [references/display-hide-map-features.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/display-hide-map-features.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/create-reusable-cross-platform-map-style.md"]` *Trigger
    Condition*: When simplifying the map display by hiding specific types of
    geographical features globally. *Evidence Checkpoint*: The targeted map
    feature category (e.g., Point of Interest icons) is no longer visible on the
    map, without affecting other features.
-   [ ] **Changes the appearance (font, size, visibility, color) of text labels
    and associated icons on the base map.** Read
    [references/change-the-style-icons-and-text-labels-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/change-the-style-icons-and-text-labels-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/create-reusable-cross-platform-map-style.md"]` *Trigger
    Condition*: When updating the visual aesthetic of text elements on the map
    for branding consistency or readability via styling rules. *Evidence
    Checkpoint*: Map labels and icons appear styled according to the specified
    rule set (e.g., label text is set to a custom color or font weight).
-   [ ] **Defines styling rules that automatically change based on the current
    zoom level of the map camera.** Read
    [references/apply-different-map-styles-different-zoom-levels.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/apply-different-map-styles-different-zoom-levels.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/create-reusable-cross-platform-map-style.md"]` *Trigger
    Condition*: When map features need to emphasize or de-emphasize based on the
    user's current zoom level (e.g., showing more detail when zoomed in).
    *Evidence Checkpoint*: Changing the zoom level triggers an observable change
    in the style of map features, such as increased line thickness or changed
    feature colors.
-   [ ] **Adjusts the number of visible Points of Interest (POIs) that are
    displayed natively on the map.** Read
    [references/change-the-density-places-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/change-the-density-places-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/create-reusable-cross-platform-map-style.md"]` *Trigger
    Condition*: When needing to control map clutter by setting rules that
    increase or decrease the prominence or density of default POIs. *Evidence
    Checkpoint*: The density of visible default POI labels and icons changes
    significantly according to the applied style density rule.
-   [ ] **Customizes the color, visibility, and texture of 3D building models
    rendered on the map.** Read
    [references/change-the-style-buildings-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/change-the-style-buildings-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/create-reusable-cross-platform-map-style.md"]` *Trigger
    Condition*: When modifying the visual appearance of building structures,
    especially in maps enabling tilt or 3D views. *Evidence Checkpoint*:
    Building footprints or 3D models appear with the specified custom style
    properties, such as a uniform gray fill color.
-   [ ] **Applies custom styling rules specifically to major geographical or
    cultural landmarks on the map.** Read
    [references/change-the-style-landmarks-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/change-the-style-landmarks-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/create-reusable-cross-platform-map-style.md"]` *Trigger
    Condition*: When highlighting or muting prominent landmarks relative to
    other map features using styling JSON. *Evidence Checkpoint*: Specified
    landmarks reflect the defined custom style attributes, such as removing
    their default iconography or changing their label appearance.

#### 🗺️ Feature Module: Data-driven styling for boundaries (Optional - Use-Case Dependent)

-   [ ] **Uses data-driven styling capabilities to apply colors, strokes, or
    visibility changes to administrative boundaries (e.g., countries, states).**
    Read
    [references/change-the-style-boundaries-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/change-the-style-boundaries-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When preparing to visualize data associated with
    specific administrative regions directly on the map, requiring boundary
    feature IDs. *Evidence Checkpoint*: Boundaries load with style attributes
    (e.g., fill color, stroke weight) defined by the associated feature layer
    configuration.
-   [ ] **Captures click or tap events specific to styled administrative
    boundaries provided by the data-driven styling features.** Read
    [references/respond-user-interactions-with-boundaries-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/respond-user-interactions-with-boundaries-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/change-the-style-boundaries-map.md"]` *Trigger Condition*: When
    needing to trigger an action (like displaying data metrics) upon user
    selection of a boundary area. *Evidence Checkpoint*: Clicking a boundary
    area successfully executes the registered event listener, retrieving the
    unique ID of the clicked boundary feature.
-   [ ] **Applies a color gradient to administrative boundaries based on
    associated quantitative data values, creating a thematic map.** Read
    [references/add-choropleth-styling-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-choropleth-styling-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/change-the-style-boundaries-map.md"]` *Trigger Condition*: When
    visualizing statistical data distribution (e.g., population density or
    election results) across geographical regions using color intensity.
    *Evidence Checkpoint*: Boundaries are shaded according to the data values
    provided, showing a clear visual gradient based on the dataset metrics.

#### 🗺️ Feature Module: Datasets (Optional - Use-Case Dependent)

-   [ ] **Parses and renders features defined within a KML or KMZ file directly
    onto the map as shapes and markers using the Utility Library.** Read
    [references/add-kml-layer-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-kml-layer-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When needing to display complex geographical data
    packaged in the Keyhole Markup Language format. *Evidence Checkpoint*:
    KML/KMZ features (placemarks, lines, polygons) appear correctly positioned
    and styled on the map using the KML layer API.
-   [ ] **Renders geographical features (points, lines, polygons) defined in a
    GeoJSON format data structure onto the map using the Utility Library.** Read
    [references/add-geojson-data-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-geojson-data-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When displaying geospatial data provided in the
    industry-standard GeoJSON format. *Evidence Checkpoint*: GeoJSON features
    are parsed from the input stream and visualized accurately on the map canvas
    with default or specified styles.
-   [ ] **Visualizes the spatial distribution and density of data points using
    color gradients and intensity zones.** Read
    [references/add-heatmap-layer-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-heatmap-layer-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When representing the density of numerous data points
    (e.g., crime statistics, sensor readings) in a visually effective, aggregate
    manner. *Evidence Checkpoint*: A smooth color gradient overlay appears on
    the map, highlighting areas of high data point concentration.

#### 🗺️ Feature Module: Data-driven styling for datasets (Optional - Use-Case Dependent)

-   [ ] **Initializes and integrates a pre-created geospatial dataset from the
    Maps Platform into the SDK for styling and interaction.** Read
    [references/create-reusable-cross-platform-geospatial-dataset.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/create-reusable-cross-platform-geospatial-dataset.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When preparing to use cloud-managed, data-driven
    styling capabilities for vector data features. *Evidence Checkpoint*: The
    defined Dataset is successfully loaded using its ID and linked to the map
    instance, making its features available for styling.
-   [ ] **Displays features from a custom geospatial dataset onto the map
    layer.** Read
    [references/add-custom-geospatial-dataset-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-custom-geospatial-dataset-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/create-reusable-cross-platform-geospatial-dataset.md"]` *Trigger
    Condition*: When rendering vector data (points, lines, polygons) sourced
    from a custom, managed dataset onto the map. *Evidence Checkpoint*: Features
    from the custom dataset layer are visible on the map, overlaid on the base
    map tiles.
-   [ ] **Applies dynamic styling (color, size, visibility) to features within a
    custom dataset based on associated data attributes.** Read
    [references/change-the-style-custom-dataset-features-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/change-the-style-custom-dataset-features-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/add-custom-geospatial-dataset-map.md"]` *Trigger Condition*:
    When visually distinguishing features in a custom dataset based on their
    underlying data properties (e.g., coloring points based on 'severity'
    attribute). *Evidence Checkpoint*: Dataset features are styled dynamically
    based on defined attribute rules, demonstrating that the data-driven styling
    engine is active.
-   [ ] **Implements event handlers for user interactions (clicks, taps)
    specific to features in a loaded custom dataset.** Read
    [references/respond-user-interactions-with-custom-dataset-features-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/respond-user-interactions-with-custom-dataset-features-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/add-custom-geospatial-dataset-map.md"]` *Trigger Condition*:
    When enabling user interaction capabilities for elements sourced from a
    data-driven layer to trigger context menus or information display. *Evidence
    Checkpoint*: Clicking a custom dataset feature triggers the associated event
    listener, providing access to the feature's specific data attributes.

#### 🗺️ Feature Module: Maps annotations (Optional - Use-Case Dependent)

-   [ ] **Displays a pop-up window containing text or custom content above a
    marker or other point of interest when interacted with.** Read
    [references/add-info-window-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-info-window-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/add-marker-map.md"]` *Trigger Condition*: When presenting
    contextual information related to a specific location marked by an
    annotation. *Evidence Checkpoint*: Clicking a marker causes the info window
    to appear, displaying the specified title, snippet, or custom view layout.
-   [ ] **Draws vector graphics (Polylines, Polygons, Circles) onto the map
    canvas based on coordinate lists.** Read
    [references/add-shape-line-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-shape-line-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When visualizing routes, boundaries, or area coverage
    on the map that are not based on KML or GeoJSON files. *Evidence
    Checkpoint*: The defined vector shape (e.g., a Polyline or Polygon) is
    successfully rendered with the specified style (color, stroke width).
-   [ ] **Places an image (Ground Overlay) that is fixed to geographical
    coordinates and rotates/scales with the map camera.** Read
    [references/add-overlay-image-grounded-the-surface-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-overlay-image-grounded-the-surface-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When displaying custom raster data, maps, or floor
    plans fixed to a specific map area. *Evidence Checkpoint*: The specified
    image is loaded and correctly draped over the defined geographical bounds on
    the map.
-   [ ] **Adds a layer constructed from custom raster image tiles (e.g., weather
    data, historical maps) fetched via a custom provider.** Read
    [references/add-custom-tile-overlay-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-custom-tile-overlay-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When displaying large amounts of custom imagery or
    raster data that is optimized for tiling and zooming. *Evidence Checkpoint*:
    Custom tiles are fetched via the provider implementation and displayed
    seamlessly as a layer over the base map.
-   [ ] **Places a standard pin graphic (Marker) at a specified
    latitude/longitude coordinate on the map.** Read
    [references/add-marker-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-marker-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When marking a specific point of interest or location
    on the map view for user reference. *Evidence Checkpoint*: A marker icon
    appears clearly positioned at the defined geographical coordinates.
-   [ ] **Changes the icon, anchor point, opacity, or shadow properties of an
    existing marker.** Read
    [references/customize-marker-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/customize-marker-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/add-marker-map.md"]` *Trigger Condition*: When customizing the
    visual appearance of map markers to align with branding or denote status.
    *Evidence Checkpoint*: The marker displays the custom icon or styling
    properties (e.g., custom bitmap, reduced opacity).
-   [ ] **Defines actions to take when a user clicks, drags, or long-presses a
    marker.** Read
    [references/respond-user-interactions-with-markers-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/respond-user-interactions-with-markers-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md",
    "references/add-marker-map.md"]` *Trigger Condition*: When the application
    needs to respond logically to user interaction with map pins, such as
    opening a detail screen. *Evidence Checkpoint*: Clicking the marker executes
    the registered `OnMarkerClickListener` callback.

#### 🗺️ Feature Module: Maps (Optional - Use-Case Dependent)

-   [ ] **Configures the map by fetching settings (like styling or feature
    enablement) associated with a specific Map ID created in the Cloud
    Console.** Read
    [references/create-reusable-map-identifier-store-map-configuration-and-styling-settings.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/create-reusable-map-identifier-store-map-configuration-and-styling-settings.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When applying consistent, cloud-managed configuration
    and styling across different application instances or platforms using a
    single identifier. *Evidence Checkpoint*: The map loads with the
    configuration specified by the Cloud Map ID applied via `MapOptions` or XML
    attributes.
-   [ ] **Implements listeners for various user events, such as map clicks, long
    presses, or camera movement changes on the map canvas.** Read
    [references/respond-user-interactions-and-events-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/respond-user-interactions-and-events-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When specific logic must be executed based on how the
    user interacts directly with the map background or framework. *Evidence
    Checkpoint*: The registered callback method (e.g., `OnMapClickListener`)
    executes successfully upon user interaction, returning geographic
    coordinates.
-   [ ] **Enables or disables built-in UI controls like zoom buttons, tilt
    gestures, or the compass.** Read
    [references/customize-the-controls-that-appear-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/customize-the-controls-that-appear-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When needing to customize the native UI elements shown
    on the map interface or remove them entirely. *Evidence Checkpoint*:
    Specified UI controls (e.g., zoom controls) are visible or hidden according
    to the applied settings configured via `UiSettings`.
-   [ ] **Enables or disables user gestures such as panning, tilting, rotating,
    and zooming.** Read
    [references/customize-the-gestures-for-controlling-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/customize-the-gestures-for-controlling-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When limiting map movement controls to focus the user
    experience or prevent unintended movement. *Evidence Checkpoint*: Attempts
    to use a disabled gesture (e.g., rotation) have no effect on the map view
    camera.
-   [ ] **Programmatically moves the map camera to a specific geographical
    location, zoom level, bearing, or tilt using `CameraUpdate` objects.** Read
    [references/control-zoom-and-pan-map-camera.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/control-zoom-and-pan-map-camera.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When needing to center the map view on a specific
    location, animate movement, or fit bounds around multiple markers. *Evidence
    Checkpoint*: The map camera smoothly transitions to the requested
    `CameraUpdate` position and orientation.
-   [ ] **Switches the base map rendering between modes like Normal, Satellite,
    Terrain, or Hybrid.** Read
    [references/change-the-map-type.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/change-the-map-type.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When the user needs to view different geographical
    representations (e.g., satellite imagery instead of vector map). *Evidence
    Checkpoint*: The base map tiles visually switch to the selected type (e.g.,
    `MAP_TYPE_SATELLITE`) upon execution.
-   [ ] **Applies a custom style (usually via a JSON resource file) to modify
    the appearance of map features.** Read
    [references/change-the-map-color-scheme.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/change-the-map-color-scheme.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When custom branding or visual requirements necessitate
    altering the default map colors and styles. *Evidence Checkpoint*: Map
    features (roads, water, land) display the custom colors defined in the style
    rules.
-   [ ] **Overlays real-time traffic flow data onto the map, displayed as
    colored lines on roads.** Read
    [references/add-traffic-layer-map.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-traffic-layer-map.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When visualizing current road congestion or traffic
    incidents for the user by enabling the traffic layer. *Evidence Checkpoint*:
    Traffic flow lines appear overlayed on major roads, indicating density based
    on current traffic data.
-   [ ] **Adapts the Map View implementation for the specific constraints and UI
    environment of a Wear OS application.** Read
    [references/add-map-wear-app.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-map-wear-app.md).
    *Dependencies*:
    `["references/add-customizable-interactive-map-web-page-mobile-app.md"]`
    *Trigger Condition*: When integrating map functionality specifically into a
    Google Wear OS device application. *Evidence Checkpoint*: The map renders
    successfully and correctly handles input gestures designed for the circular
    or specialized Wear OS UI.

#### 🗺️ Feature Module: Street View (Optional - Use-Case Dependent)

-   [ ] **Renders the Street View panorama viewer component, initializing it to
    a specific location (Pano ID or coordinates).** Read
    [references/add-configurable-interactive-google-street-view-web-page-mobile-app.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/add-configurable-interactive-google-street-view-web-page-mobile-app.md).
    *Trigger Condition*: When providing a panoramic, ground-level immersive
    experience of a location within the application, typically triggered by a
    button press. *Evidence Checkpoint*: The Street View image loads
    successfully within the designated fragment/view, displaying the requested
    panorama.
-   [ ] **Implements listeners for events such as panorama changes or user
    clicks within the Street View display.** Read
    [references/respond-user-interactions-and-events-google-street-view.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/respond-user-interactions-and-events-google-street-view.md).
    *Dependencies*:
    `["references/add-configurable-interactive-google-street-view-web-page-mobile-app.md"]`
    *Trigger Condition*: When needing to react to how the user interacts with
    the Street View environment (e.g., notifying when the user moves to a new
    panorama). *Evidence Checkpoint*: User actions (e.g., clicking or changing
    panorama) trigger the appropriate event callback methods, returning data
    about the change.
-   [ ] **Enables or disables user controls like zoom, panning, and movement
    between panoramas in the Street View interface.** Read
    [references/customize-the-gestures-that-are-available-for-google-street-view.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/customize-the-gestures-that-are-available-for-google-street-view.md).
    *Dependencies*:
    `["references/add-configurable-interactive-google-street-view-web-page-mobile-app.md"]`
    *Trigger Condition*: When controlling how users can navigate and interact
    with the Street View visualization for specific user experiences. *Evidence
    Checkpoint*: Disabled gestures (e.g., zooming) no longer affect the Street
    View rendering, ensuring interaction is limited.
-   [ ] **Programmatically controls the view angle, pitch, and zoom of the
    Street View camera with smooth transitions.** Read
    [references/animate-the-camera-movements-for-google-street-view.md](https://www.gstatic.com/googlemapsplatform-agent-skills/maps-sdk-for-android-android/references/animate-the-camera-movements-for-google-street-view.md).
    *Dependencies*:
    `["references/add-configurable-interactive-google-street-view-web-page-mobile-app.md"]`
    *Trigger Condition*: When guiding the user's focus or transitioning the view
    between different perspectives within the same panorama. *Evidence
    Checkpoint*: The Street View camera smoothly animates to the new requested
    orientation parameters (e.g., bearing and tilt).
