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
	else if (keyName === 'Home') {
		navigate("home");
	}
	else if (keyName === 'End') {
		navigate("end");
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