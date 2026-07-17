// Loader de CSS para el bundle de :fullstack (hoy sólo lo consume `jsTest`).
//
// Por qué existe: el bundle enlaza el módulo completo, que usa KVision, y KVision arrastra
// `toastify-js/src/toastify.css` transitivamente. Sin este loader webpack falla con
// `Module parse failed: Unexpected token` sobre `.toastify { ... }` y **`:fullstack:jsTest` no corre
// en absoluto** — ni siquiera los tests que no tocan KVision, porque el fallo es del bundle, no del
// test. Espeja `arel/webpack.config.d/css.js` de mppArel, que ya resuelve lo mismo.
config.module.rules.push({ test: /\.css$/, use: ["style-loader", { loader: "css-loader", options: { sourceMap: false } }] });
