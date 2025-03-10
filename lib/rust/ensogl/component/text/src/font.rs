//! Definition of font, font face, and font registry. Aggregates information and utilities for
//! working with fonts.

use crate::prelude::*;

use enso_shapely::shared;
use ensogl_core::display::scene;
use ensogl_text_embedded_fonts::Embedded;
use ensogl_text_msdf as msdf;
use ordered_float::NotNan;
use owned_ttf_parser as ttf;
use std::collections::hash_map::Entry;
use ttf::AsFaceRef;


// ==============
// === Export ===
// ==============

pub mod glyph;
pub mod glyph_render_info;
pub mod pen;

pub use ensogl_text_font_family as family;
pub use family::Name;
pub use family::NonVariableFaceHeader;
pub use glyph_render_info::GlyphRenderInfo;
pub use ttf::GlyphId;
pub use ttf::Style;
pub use ttf::Tag;
pub use ttf::Weight;
pub use ttf::Width;



// =================
// === Constants ===
// =================

/// TTF files can contain multiple face definitions. We support only the first defined, just as
/// most web browsers (you cannot define `@font-face` in CSS for multiple faces of the same file).
const TTF_FONT_FACE_INDEX: u32 = 0;

/// A string literal that means a default non-monospace font.
pub const DEFAULT_FONT: &str = "default";

/// A string literal that means a default monospace font.
pub const DEFAULT_FONT_MONO: &str = "default-mono";



// =====================
// === VariationAxis ===
// =====================

/// A variation axis of variable fonts. The axis name is [`Tag`], which is a 4-bytes identifier
/// constructed from the axis name, e.g. by `Tag::from_bytes(b"ital")`. See the following link to
/// learn more:
/// https://docs.microsoft.com/en-us/typography/opentype/spec/dvaraxisreg#registered-axis-tags
#[allow(missing_docs)]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct VariationAxis {
    tag:   Tag,
    value: NotNan<f32>,
}

impl VariationAxis {
    /// Constructor
    pub fn new(tag: Tag, value: NotNan<f32>) -> Self {
        Self { tag, value }
    }

    /// Constructor.
    pub fn from_bytes(bytes: &[u8; 4], value: NotNan<f32>) -> Self {
        let tag = Tag::from_bytes(bytes);
        Self { tag, value }
    }
}



// =====================
// === VariationAxes ===
// =====================

/// Variation axes of variable fonts.
#[allow(missing_docs)]
#[derive(Debug, Default, Clone, PartialEq, Eq, Hash)]
pub struct VariationAxes {
    pub vec: Vec<VariationAxis>,
}

impl VariationAxes {
    /// Map a function over all standard axes. Not all fonts have to support them, but it is a good
    /// idea to set these values when loading a font. Otherwise, some fonts might not be visible
    /// on the screen, as for example their width might default to zero.
    pub fn with_default_axes_values(f: impl Fn(VariationAxis)) {
        let mut axes = Self::default();
        axes.set_weight(Weight::Normal);
        axes.set_width(Width::Normal);
        axes.set_style(Style::Normal);
        axes.with_axes(f);
    }

    /// Map a function over all changed axes.
    pub fn with_axes(&self, f: impl Fn(VariationAxis)) {
        for axis in &self.vec {
            f(*axis);
        }
    }

    /// Variation axis setter.
    pub fn set(&mut self, axis: VariationAxis) {
        if let Some(index) = self.vec.iter().position(|a| a.tag == axis.tag) {
            self.vec[index] = axis;
        } else {
            self.vec.push(axis);
        }
    }

    /// Variation axis setter. “Italic” (`ital` in CSS) is an axis found in some variable fonts. It
    /// controls the font file’s italic parameter, with italics either turned “off” or “on”, rather
    /// than gradually changing over a range. The Google Fonts CSS v2 API defines the axis as:
    /// Default: 0   Min: 0   Max: 1   Step: 0.1
    /// https://fonts.google.com/knowledge/glossary/italic_axis
    pub fn set_ital(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"ital", value));
    }

    /// Variation axis setter. “Optical Size” (controlled with `font-optical-sizing` or
    /// `font-variation-setting`: ‘opsz’ VALUE in CSS) is an axis found in some variable fonts. It
    /// controls the font file’s optical size optimizations. The Google Fonts CSS v2 API defines the
    /// axis as:
    /// Default: 14   Min: 6   Max: 144   Step: 0.1
    /// https://fonts.google.com/knowledge/glossary/optical_size_axis
    pub fn set_opsz(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"opsz", value));
    }

    /// Variation axis setter. Slant (`slnt` in CSS) is an axis found in some variable fonts. It
    /// controls the font file’s slant parameter for oblique styles. The Google Fonts CSS v2 API
    /// defines the axis as:
    /// Default: 0   Min: -90   Max: 90   Step: 1
    /// https://fonts.google.com/knowledge/glossary/slant_axis
    pub fn set_slnt(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"slnt", value));
    }

    /// Variation axis setter. “Weight” (`wght` in CSS) is an axis found in many variable fonts. It
    /// controls the font file’s weight parameter. The Google Fonts CSS v2 API defines the axis as:
    /// Default: 400   Min: 1   Max: 1000   Step: 1
    /// https://fonts.google.com/knowledge/glossary/weight_axis
    pub fn set_wght(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"wght", value));
    }

    /// Variation axis setter. “Width” (`wdth` in CSS) is an axis found in some variable fonts. It
    /// controls the font file’s width parameter. The Google Fonts CSS v2 API defines the axis as:
    /// Default: 100   Min: 25   Max: 200   Step: 0.1
    /// https://fonts.google.com/knowledge/glossary/width_axis
    pub fn set_wdth(&mut self, value: NotNan<f32>) {
        self.set(VariationAxis::from_bytes(b"wdth", value));
    }

    /// Weight setter.
    pub fn set_weight(&mut self, value: Weight) {
        self.set_wght(value.to_number().into());
    }

    /// Width setter.
    pub fn set_width(&mut self, value: Width) {
        let wdth = match value {
            Width::UltraCondensed => 25.0,
            Width::ExtraCondensed => 43.75,
            Width::Condensed => 62.5,
            Width::SemiCondensed => 81.25,
            Width::Normal => 100.0,
            Width::SemiExpanded => 118.75,
            Width::Expanded => 137.5,
            Width::ExtraExpanded => 156.25,
            Width::UltraExpanded => 175.0,
        };
        self.set_wdth(NotNan::new(wdth).unwrap());
    }

    /// Style setter.
    pub fn set_style(&mut self, value: Style) {
        match value {
            Style::Normal => {
                self.set_ital(0_u16.into());
                self.set_slnt(0_u16.into());
            }
            Style::Italic => {
                self.set_ital(1_u16.into());
                self.set_slnt(0_u16.into());
            }
            Style::Oblique => {
                self.set_ital(0_u16.into());
                self.set_slnt(90_u16.into());
            }
        }
    }
}



// ============
// === Face ===
// ============

/// A face of a font. In case of non-variable fonts, a face corresponds to a font variation defined
/// as a triple (width, weight, style), see [`NonVariableFaceHeader`]. In case of variable fonts,
/// the font variation ([`VariationAxes`]) is set up at runtime, so only one face is needed.
///
/// The face consists of a [ttf face](ttf::OwnedFace) and [MSDF one](msdf::OwnedFace). The former
/// contains all information needed to layout glyphs, like kerning. The latter is used to generate
/// MSDF textures for glyphs.
#[allow(missing_docs)]
#[derive(Debug)]
pub struct Face {
    pub msdf: msdf::OwnedFace,
    pub ttf:  ttf::OwnedFace,
}

impl Face {
    /// Load the font face from memory. Corrupted faces will be reported.
    fn load_from_memory(name: &str, embedded: &Embedded) -> Option<Face> {
        embedded.data.get(name).and_then(|data| {
            let result =
                ttf::OwnedFace::from_vec((**data).into(), TTF_FONT_FACE_INDEX).map(|ttf| {
                    let msdf = msdf::OwnedFace::load_from_memory(data);
                    Face { msdf, ttf }
                });
            result.map_err(|err| event!(ERROR, "Error parsing font: {}", err)).ok()
        })
    }
}



// ==============
// === Family ===
// ==============

/// A generalization of a font family, a set of font faces. Allows borrowing a font face based on
/// variations.
pub trait Family {
    /// For non-variable fonts, variations is a triple (width, weight, style), see
    /// [`NonVariableFaceHeader`] to learn more. For variable faces, the variation is
    /// [`VariationAxes`], however, as variable fonts have one face only, this parameter is not
    /// used while borrowing the face.
    type Variations: Eq + Hash + Clone + Debug;

    /// Update MSDFgen settings for given variations. For non-variable fonts, this function is a
    /// no-op.
    fn update_msdfgen_variations(&self, variations: &Self::Variations);
    /// Run the function with borrowed font face for the given variations set. For non-variable
    /// fonts, the function will not be run if variations do not match a known definition. For
    /// variable fonts, the function always succeeds.
    fn with_borrowed_face<F, T>(&self, variations: &Self::Variations, f: F) -> Option<T>
    where F: for<'a> FnOnce(&'a Face) -> T;
}

/// A non-variable font family.
#[allow(missing_docs)]
#[derive(Debug)]
pub struct NonVariableFamily {
    pub definition: family::NonVariableDefinition,
    pub faces:      Rc<RefCell<HashMap<NonVariableFaceHeader, Face>>>,
}

/// A variable font family. Contains font family definition and the font face. The face is kept in
/// an `Option` because it is created after the family initialization. Currently, it could be
/// simplified, but it is already designed in this way to support on-demand face loading (served
/// from server when needed).
#[allow(missing_docs)]
#[derive(Debug)]
pub struct VariableFamily {
    pub definition: family::VariableDefinition,
    pub face:       Rc<RefCell<Option<Face>>>,
    /// Most recent axes used to generate MSDF textures. If axes change, MSDFgen parameters need to
    /// be updated, which involves a non-zero cost (mostly due to Rust <> JS interop). Thus, we
    /// want to refresh them only when needed. This field is a cache allowing us to check if
    /// axes changed.
    pub last_axes:  Rc<RefCell<Option<VariationAxes>>>,
}

impl NonVariableFamily {
    /// Load all font faces from the embedded font data. Corrupted faces will be reported and
    /// ignored.
    fn load_all_faces(&self, embedded: &Embedded) {
        for (header, file_name) in &self.definition.map {
            if let Some(face) = Face::load_from_memory(&*file_name, embedded) {
                self.faces.borrow_mut().insert(*header, face);
            }
        }
    }
}

impl VariableFamily {
    /// Load all font faces from the embedded font data. Corrupted faces will be reported and
    /// ignored.
    fn load_all_faces(&self, embedded: &Embedded) {
        if let Some(face) = Face::load_from_memory(&self.definition.file_name, embedded) {
            // Set default variation axes during face initialization. This is needed to make some
            // fonts appear on the screen. In case some axes are not found, warnings will be
            // silenced.
            VariationAxes::with_default_axes_values(|axis| {
                face.msdf.set_variation_axis(axis.tag, axis.value.into_inner() as f64).ok();
            });
            self.face.borrow_mut().replace(face);
        }
    }
}

impl Family for NonVariableFamily {
    type Variations = NonVariableFaceHeader;
    fn update_msdfgen_variations(&self, _variations: &Self::Variations) {}
    fn with_borrowed_face<F, T>(&self, variations: &Self::Variations, f: F) -> Option<T>
    where F: for<'a> FnOnce(&'a Face) -> T {
        self.faces.borrow().get(variations).map(f)
    }
}

impl Family for VariableFamily {
    type Variations = VariationAxes;
    fn update_msdfgen_variations(&self, variations: &Self::Variations) {
        if let Some(face) = self.face.borrow().as_ref() {
            if self.last_axes.borrow().as_ref() != Some(variations) {
                self.last_axes.borrow_mut().replace(variations.clone());
                variations.with_axes(|axis| {
                    let value = axis.value.into_inner() as f64;
                    face.msdf
                        .set_variation_axis(axis.tag, value)
                        .map_err(|err| {
                            event!(WARN, "Error setting font variation axis: {}", err);
                        })
                        .ok();
                });
            }
        }
    }

    fn with_borrowed_face<F, T>(&self, _variations: &Self::Variations, f: F) -> Option<T>
    where F: for<'a> FnOnce(&'a Face) -> T {
        self.face.borrow().as_ref().map(f)
    }
}

impl From<&family::VariableDefinition> for VariableFamily {
    fn from(definition: &family::VariableDefinition) -> Self {
        let definition = definition.clone();
        Self { definition, face: default(), last_axes: default() }
    }
}

impl From<&family::NonVariableDefinition> for NonVariableFamily {
    fn from(definition: &family::NonVariableDefinition) -> Self {
        let definition = definition.clone();
        Self { definition, faces: default() }
    }
}



// ============
// === Font ===
// ============

/// A typeface, commonly referred to as a font.
#[allow(missing_docs)]
#[derive(Debug, Clone, CloneRef, From)]
pub enum Font {
    NonVariable(NonVariableFont),
    Variable(VariableFont),
}

/// A non-variable version of [`Font`].
pub type NonVariableFont = FontTemplate<NonVariableFamily>;

/// A variable version of [`Font`].
pub type VariableFont = FontTemplate<VariableFamily>;

impl Font {
    /// List all possible weights. In case of variable fonts, [`None`] will be returned.
    pub fn possible_weights(&self) -> Option<Vec<Weight>> {
        match self {
            Font::NonVariable(font) => Some(font.family.definition.possible_weights()),
            Font::Variable(_) => None,
        }
    }

    /// Get render info for one character, generating one if not found.
    pub fn glyph_info(
        &self,
        non_variable_font_variations: NonVariableFaceHeader,
        variable_font_variations: &VariationAxes,
        glyph_id: GlyphId,
    ) -> Option<GlyphRenderInfo> {
        match self {
            Font::NonVariable(font) => font.glyph_info(&non_variable_font_variations, glyph_id),
            Font::Variable(font) => font.glyph_info(variable_font_variations, glyph_id),
        }
    }

    // FIXME[WD]: Remove after all APIs will use GlyphIds (incl. pen API).
    //   https://www.pivotaltracker.com/story/show/182746060
    /// Get the glyph id of the provided code point.
    pub fn glyph_id_of_code_point(
        &self,
        non_variable_font_variations: NonVariableFaceHeader,
        variable_font_variations: &VariationAxes,
        code_point: char,
    ) -> Option<GlyphId> {
        match self {
            Font::NonVariable(font) =>
                font.glyph_id_of_code_point(&non_variable_font_variations, code_point),
            Font::Variable(font) =>
                font.glyph_id_of_code_point(variable_font_variations, code_point),
        }
    }

    /// Get number of rows in MSDF texture.
    pub fn msdf_texture_rows(&self) -> usize {
        match self {
            Font::NonVariable(font) => font.msdf_texture_rows(),
            Font::Variable(font) => font.msdf_texture_rows(),
        }
    }

    /// A whole MSDF texture bound for this font.
    pub fn with_borrowed_msdf_texture_data<R>(&self, operation: impl FnOnce(&[u8]) -> R) -> R {
        match self {
            Font::NonVariable(font) => font.with_borrowed_msdf_texture_data(operation),
            Font::Variable(font) => font.with_borrowed_msdf_texture_data(operation),
        }
    }

    /// Get kerning between two characters.
    pub fn kerning(
        &self,
        non_variable_font_variations: NonVariableFaceHeader,
        variable_font_variations: &VariationAxes,
        left: GlyphId,
        right: GlyphId,
    ) -> f32 {
        match self {
            Font::NonVariable(font) => font.kerning(&non_variable_font_variations, left, right),
            Font::Variable(font) => font.kerning(variable_font_variations, left, right),
        }
    }
}



// ====================
// === FontTemplate ===
// ====================

/// Internal representation of [`Font`]. It contains references to the font family definition,
/// a texture with MSDF-encoded glyph shapes, and a cache for common glyph properties, used to
/// layout glyphs.
#[derive(Deref, Derivative, CloneRef, Debug)]
#[derivative(Clone(bound = ""))]
pub struct FontTemplate<F: Family> {
    rc: Rc<FontTemplateData<F>>,
}

/// Internal representation of [`FontTemplate`].
#[derive(Debug)]
#[allow(missing_docs)]
pub struct FontTemplateData<F: Family> {
    pub name:                   Name,
    pub family:                 F,
    pub atlas:                  msdf::Texture,
    pub cache:                  RefCell<HashMap<F::Variations, FontDataCache>>,
    // FIXME[WD]: Remove after all APIs will use GlyphIds (incl. pen API).
    //   https://www.pivotaltracker.com/story/show/182746060
    pub glyph_id_to_code_point: RefCell<HashMap<GlyphId, char>>,
}

/// A cache for common glyph properties, used to layout glyphs.
#[derive(Debug, Default)]
pub struct FontDataCache {
    kerning: HashMap<(GlyphId, GlyphId), f32>,
    glyphs:  HashMap<GlyphId, GlyphRenderInfo>,
}

impl<F: Family> From<FontTemplateData<F>> for FontTemplate<F> {
    fn from(t: FontTemplateData<F>) -> Self {
        let rc = Rc::new(t);
        Self { rc }
    }
}

impl<F: Family> FontTemplate<F> {
    /// Constructor.
    pub fn new(name: Name, family: impl Into<F>) -> Self {
        let atlas = default();
        let cache = default();
        let family = family.into();
        let glyph_id_to_code_point = default();
        let data = FontTemplateData { name, family, atlas, cache, glyph_id_to_code_point };
        Self { rc: Rc::new(data) }
    }

    // FIXME[WD]: Remove after all APIs will use GlyphIds (incl. pen API).
    //   https://www.pivotaltracker.com/story/show/182746060
    /// Get the glyph id of the provided code point.
    pub fn glyph_id_of_code_point(
        &self,
        variations: &F::Variations,
        code_point: char,
    ) -> Option<GlyphId> {
        self.family
            .with_borrowed_face(variations, |face| {
                face.ttf.as_face_ref().glyph_index(code_point).map(|id| {
                    self.glyph_id_to_code_point.borrow_mut().insert(id, code_point);
                    id
                })
            })
            .flatten()
    }

    /// Get render info for one character, generating one if not found.
    pub fn glyph_info(
        &self,
        variations: &F::Variations,
        glyph_id: GlyphId,
    ) -> Option<GlyphRenderInfo> {
        let opt_render_info =
            self.cache.borrow().get(variations).and_then(|t| t.glyphs.get(&glyph_id)).copied();
        if opt_render_info.is_some() {
            opt_render_info
        } else {
            self.family.update_msdfgen_variations(variations);
            self.family.with_borrowed_face(variations, |face| {
                let render_info = GlyphRenderInfo::load(&face.msdf, glyph_id, &self.atlas);
                if !self.cache.borrow().contains_key(variations) {
                    self.cache.borrow_mut().insert(variations.clone(), default());
                }
                let mut borrowed_cache = self.cache.borrow_mut();
                let font_data_cache = borrowed_cache.get_mut(variations).unwrap();
                font_data_cache.glyphs.insert(glyph_id, render_info);
                render_info
            })
        }
    }

    /// Get kerning between two characters.
    pub fn kerning(&self, variations: &F::Variations, left: GlyphId, right: GlyphId) -> f32 {
        self.family
            .with_borrowed_face(variations, |face| {
                if !self.cache.borrow().contains_key(variations) {
                    self.cache.borrow_mut().insert(variations.clone(), default());
                }
                let mut borrowed_cache = self.cache.borrow_mut();
                let font_data_cache = borrowed_cache.get_mut(variations).unwrap();
                *font_data_cache.kerning.entry((left, right)).or_insert_with(|| {
                    let tables = face.ttf.as_face_ref().tables();
                    let units_per_em = tables.head.units_per_em;
                    let kern_table = tables.kern.and_then(|t| t.subtables.into_iter().next());
                    let kerning = kern_table.and_then(|t| t.glyphs_kerning(left, right));
                    kerning.unwrap_or_default() as f32 / units_per_em as f32
                })
            })
            .unwrap_or_default()
    }

    /// A whole MSDF texture bound for this font.
    pub fn with_borrowed_msdf_texture_data<R>(&self, operation: impl FnOnce(&[u8]) -> R) -> R {
        self.atlas.with_borrowed_data(operation)
    }

    /// Get number of rows in MSDF texture.
    pub fn msdf_texture_rows(&self) -> usize {
        self.atlas.rows()
    }
}



// ================
// === Registry ===
// ================

shared! { Registry
/// Structure keeping all fonts loaded from different sources.
#[derive(Debug)]
pub struct RegistryData {
    embedded: Embedded,
    fonts:    HashMap<Name,Font>,
}

impl {
    /// Load the default font. See the docs of [`load`] to learn more.
    pub fn load_default(&mut self) -> Font {
        self.load(DEFAULT_FONT)
    }

    /// Load a font by name. The font can be loaded either from cache or from the embedded fonts'
    /// registry if not used before. Returns the default font if the name is missing in both cache
    /// and embedded font list.
    pub fn load(&mut self, name:impl Into<Name>) -> Font {
        let name = name.into();
        self.try_load(&name).unwrap_or_else(|| {
            event!(WARN, "Font '{name}' not found. Loading the default font.");
            self.try_load(DEFAULT_FONT).expect("Default font not found.")
        })
    }

    /// Load a font by name. The font can be loaded either from cache or from the embedded fonts'
    /// registry if not used before. Returns [`None`] if the name is missing in both cache and
    /// embedded font list.
    pub fn try_load(&mut self, name:impl Into<Name>) -> Option<Font> {
        let name = name.into();
        event!(DEBUG, "Loading font: {:?}", name);
        match self.fonts.entry(name.clone()) {
            Entry::Occupied (entry) => Some(entry.get().clone_ref()),
            Entry::Vacant   (entry) => {
                self.embedded.definitions.get(&name).map(|definition| {
                    let font: Font = match definition {
                        family::Definition::NonVariable(definition) => {
                            let family = NonVariableFamily::from(definition);
                            family.load_all_faces(&self.embedded);
                            NonVariableFont::new(name, family).into()
                        }
                        family::Definition::Variable(definition) => {
                            let family = VariableFamily::from(definition);
                            family.load_all_faces(&self.embedded);
                            VariableFont::new(name, family).into()
                        }
                    };
                    entry.insert(font.clone_ref());
                    font
                })
            }
        }
    }
}}

impl Registry {
    /// Constructor.
    pub fn init_and_load_embedded_fonts() -> Registry {
        let embedded = Embedded::init_and_load_embedded_fonts();
        let fonts = HashMap::new();
        let data = RegistryData { embedded, fonts };
        let rc = Rc::new(RefCell::new(data));
        Self { rc }
    }
}

impl scene::Extension for Registry {
    fn init(_scene: &scene::Scene) -> Self {
        Self::init_and_load_embedded_fonts()
    }
}
