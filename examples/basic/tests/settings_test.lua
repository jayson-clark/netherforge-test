-- A setting is what its declaration in netherforge.json says until the server's owner changes it.
nf.test.case("settings read as their defaults", function()
  assert(nf.config("greeting") == "Welcome")
  assert(nf.config("show_welcome") == true)
  assert(nf.config("treasure_rolls") == 1)
end)
