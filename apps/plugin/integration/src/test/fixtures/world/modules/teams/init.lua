local red = nf.teams.create("red", {
  display_name = "<red>Red",
  prefix = "<red>[R] ",
  suffix = " *",
  color = "red",
  friendly_fire = false,
  see_invisible_teammates = false,
  nametags = "hide_for_other_teams",
  collision = "push_own_team",
})
nf.commands.register("it-teams", function()
  local zombie = nf.worlds.default():spawn_entity("minecraft:zombie", vec3(215.5, -62, 215.5))
  zombie:set_ai(false)
  local added = red:add_member(zombie)
  local members = red:members()
  local set = red:set_prefix("<blue>[B] ")
    and red:set_suffix("")
    and red:set_display_name("Blue")
    and red:set_color("blue")
    and red:set_friendly_fire(true)
    and red:set_can_see_invisible_teammates(true)
    and red:set_nametags("never")
    and red:set_collision("never")
  log(
    "teams",
    added,
    zombie:team() == red,
    red:has_member(zombie),
    #members == 1 and members[1] == zombie,
    red:color(),
    red:nametags(),
    set,
    red:remove_member(zombie),
    zombie:team() == nil
  )
  red:add_member(zombie)
  log("team removed", red:remove(), red:exists(), zombie:team() == nil, #nf.teams.all())
  zombie:remove()
end)
