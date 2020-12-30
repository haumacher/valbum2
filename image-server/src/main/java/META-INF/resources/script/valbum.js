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


function onImageZoom(event) {
	var container = document.getElementById("image-container");
	var image = document.getElementById("image");
	
	var scale1 = image.scale;
	if (scale1 == null) {
		scale1 = 1;
	}
	
	var scale2 = 1 - event.deltaY * 0.05;
	var newScale = scale1 * scale2;
	if (newScale <= 0.1) {
		return;
	}

	var x = event.pageX;
	var y = event.pageY;
	
	// Make event relative to the image container.
	var parent = container;
	while (parent != null) {
		x -= parent.offsetLeft;
		y -= parent.offsetTop;
		parent = parent.offsetParent;
	}
	
	x -= image.offsetLeft;
	y -= image.offsetTop;
	
	var target = event.target;
	
	var tx;
	var ty;
	if (scale1 != 1) {
		var tx1 = image.tx;
		var ty1 = image.ty;
		
		// Point in the original picture, where now should be the scale origin.
		var origX = (x - tx1) / scale1;
		var origY = (y - ty1) / scale1;
		
		tx = x - newScale * origX;
		ty = y - newScale * origY;
	} else {
		tx = (1 - scale2) * x;
		ty = (1 - scale2) * y;
	}
	
	image.style["transform-origin"] = "0px 0px";
	image.style.transform = "translate(" + tx + "px, " + ty + "px) scale(" + newScale + ")";
	
	image.scale = newScale;
	image.tx = tx;
	image.ty = ty;
	
	event.preventDefault();
}

function onLoad(event) {
	document.addEventListener("keydown", onKeyDown);
	
	var container = document.getElementById("image-container");
	if (container != null) {
		container.addEventListener("wheel", onImageZoom);
	}
}

window.addEventListener("load", onLoad);

