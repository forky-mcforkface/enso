//! Label component. Appears as text with background.

#![recursion_limit = "512"]
// === Features ===
#![feature(option_result_contains)]
#![feature(trait_alias)]
// === Standard Linter Configuration ===
#![deny(non_ascii_idents)]
#![warn(unsafe_code)]
// === Non-Standard Linter Configuration ===
#![warn(missing_copy_implementations)]
#![warn(missing_debug_implementations)]
#![warn(missing_docs)]
#![warn(trivial_casts)]
#![warn(trivial_numeric_casts)]
#![warn(unused_import_braces)]
#![warn(unused_qualifications)]

use ensogl_core::display::shape::*;
use ensogl_core::prelude::*;

use enso_frp as frp;
use ensogl_core::application::Application;
use ensogl_core::data::color;
use ensogl_core::display;
use ensogl_core::display::scene::Layer;
use ensogl_hardcoded_theme::component::label as theme;
use ensogl_shadow as shadow;
use ensogl_text as text;



// ==========================
// === Shapes Definitions ===
// ==========================

mod background {
    use super::*;

    ensogl_core::define_shape_system! {
        (style:Style,bg_color:Vector4) {

            let width      = Var::<Pixels>::from("input_size.x");
            let height     = Var::<Pixels>::from("input_size.y");
            let padding    = style.get_number(theme::padding_outer);
            let width      = width  - padding.px() * 2.0;
            let height     = height - padding.px() * 2.0;
            let radius     = &height / 2.0;
            let base_shape = Rect((&width,&height)).corners_radius(&radius);
            let shape      = base_shape.fill(Var::<color::Rgba>::from(bg_color.clone()));
            let alpha      = Var::<f32>::from(format!("({0}.w)",bg_color));
            let shadow     = shadow::from_shape_with_alpha(base_shape.into(),&alpha,style);

            (shadow+shape).into()
        }
    }
}



// ===========
// === FRP ===
// ===========

ensogl_core::define_endpoints! {
    Input {
        set_content(String),
        set_opacity(f32)
    }
    Output {
        size (Vector2)
    }
}



// =============
// === Model ===
// =============

#[derive(Clone, Debug)]
struct Model {
    background:     background::View,
    label:          text::Area,
    display_object: display::object::Instance,
    style:          StyleWatch,
}

impl Model {
    fn new(app: Application) -> Self {
        let app = app.clone_ref();
        let scene = &app.display.default_scene;
        let logger = Logger::new("TextLabel");
        let display_object = display::object::Instance::new(&logger);
        let label = app.new_view::<text::Area>();
        let background = background::View::new(&logger);

        display_object.add_child(&background);
        display_object.add_child(&label);

        let style = StyleWatch::new(&app.display.default_scene.style_sheet);
        if let Some(display::style::Data::Text(font)) = style.get(theme::text::font) {
            label.set_font(font);
        }

        let model = Model { background, label, display_object, style };
        model.set_layers(&scene.layers.tooltip, &scene.layers.tooltip_text);
        model
    }

    pub fn height(&self) -> f32 {
        self.style.get_number(theme::height)
    }

    /// Set scene layers for background and text respectively.
    pub fn set_layers(&self, background_layer: &Layer, text_layer: &Layer) {
        // FIXME[MM/WD]: Depth sorting of labels to in front of everything else in the scene.
        //  Temporary solution. The depth management needs to allow defining relative position of
        //  the text and background and let the whole component to be set to am an arbitrary layer.
        background_layer.add_exclusive(&self.background);
        self.label.add_to_scene_layer(text_layer);
    }

    fn set_width(&self, width: f32) -> Vector2 {
        let padding_outer = self.style.get_number(theme::padding_outer);
        let padding_inner_x = self.style.get_number(theme::padding_inner_x);
        let padding_inner_y = self.style.get_number(theme::padding_inner_y);
        let padding_x = padding_outer + padding_inner_x;
        let padding_y = padding_outer + padding_inner_y;
        let padding = Vector2(padding_x, padding_y);
        let text_size = self.style.get_number(theme::text::size);
        let text_offset = self.style.get_number(theme::text::offset);
        let height = self.height();
        let size = Vector2(width, height);
        let padded_size = size + padding * 2.0;
        self.background.size.set(padded_size);
        let text_origin = Vector2(text_offset - size.x / 2.0, text_size / 2.0);
        self.label.set_position_xy(text_origin);
        padded_size
    }

    fn set_content(&self, t: &str) -> Vector2 {
        self.label.set_content(t);
        self.set_width(self.label.width.value())
    }

    fn set_opacity(&self, value: f32) {
        let text_color_path = theme::text;
        let text_color = self.style.get_color(text_color_path).multiply_alpha(value);
        self.label.frp.set_color_all.emit(text_color);
        self.label.frp.set_default_color.emit(text_color);

        let bg_color_path = theme::background;
        let bg_color = self.style.get_color(bg_color_path).multiply_alpha(value);
        self.background.bg_color.set(bg_color.into())
    }
}



// =======================
// === Label Component ===
// =======================

#[allow(missing_docs)]
#[derive(Clone, CloneRef, Debug)]
pub struct Label {
    model:   Rc<Model>,
    pub frp: Rc<Frp>,
}

impl Label {
    /// Constructor.
    pub fn new(app: &Application) -> Self {
        let frp = Rc::new(Frp::new());
        let model = Rc::new(Model::new(app.clone_ref()));
        Label { model, frp }.init()
    }

    /// Set layers for Label's background and text respectively. This is needed because
    /// `text::Area` uses its own `add_to_scene_layer` method instead of utilizing more common
    /// [`Layer::add_exclusive`].
    pub fn set_layers(&self, background_layer: &Layer, text_layer: &Layer) {
        self.model.set_layers(background_layer, text_layer);
    }

    fn init(self) -> Self {
        let frp = &self.frp;
        let network = &frp.network;
        let model = &self.model;

        frp::extend! { network
            frp.source.size <+ frp.set_content.map(f!((t)
                model.set_content(t)
            ));

            eval frp.set_opacity((value) model.set_opacity(*value));
        }

        self
    }
}

impl Deref for Label {
    type Target = Frp;

    fn deref(&self) -> &Self::Target {
        &self.frp
    }
}

impl display::Object for Label {
    fn display_object(&self) -> &display::object::Instance {
        &self.model.display_object
    }
}
