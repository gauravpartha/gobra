package pkg

func test1() {
  ghost s := set[seq[int]] { { 1 : 12, 0 : 24 }, {  } }
  assert s == set[seq[int]] { {  }, { 24, 12 } }
}
