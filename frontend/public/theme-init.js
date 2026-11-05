;(function () {
  var mode = 'system'
  try {
    var stored = window.localStorage.getItem('micro.theme')
    if (stored === 'light' || stored === 'dark' || stored === 'system') mode = stored
  } catch {
    // Almacenamiento no disponible: se usa la preferencia del sistema.
  }
  var dark = mode === 'dark' || (mode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  var root = document.documentElement
  root.classList.toggle('dark', dark)
  root.style.colorScheme = dark ? 'dark' : 'light'
})()
