function onKeyDown(event) {
	var keyName = event.key;

	if (keyName === 'ArrowUp') {
		navigate("up");
	}
	else if (keyName === 'ArrowLeft') {
		navigate("left");
	}
	else if (keyName === 'ArrowRight' || keyName === ' ') {
		navigate("right");
	}
}

function navigate(attr) {
	var page = document.getElementById("page");
	if (page != null) {
		var url = page.getAttribute("data-" + attr);
		if (url != null) {
			document.location = url;
		}
	}
}

document.addEventListener("keydown", onKeyDown);