var sidebar = document.querySelector(".docs-sidebar");
if (sidebar) {
  sidebar.classList.add("menu-collapsible");
  var toggle = sidebar.querySelector(".docs-sidebar-toggle");
  toggle.addEventListener("click", function () {
    var open = sidebar.classList.toggle("open");
    toggle.setAttribute("aria-expanded", String(open));
  });
}
