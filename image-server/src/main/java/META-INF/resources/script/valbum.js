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
	var container = containerElement();
	var image = imageElement();
	
	var origScale = withDefault(image.scale, 1);
	
	var scaleBy = 1 - event.deltaY * 0.05;
	var newScale = origScale * scaleBy;
	if (newScale <= 0.1) {
		// Limit scale.
		return;
	}

	var pos = position(event, container, image);	
	
	var tx1 = withDefault(image.tx, 0);
	var ty1 = withDefault(image.ty, 0);
		
	// Point in the original picture, where now should be the scale origin.
	var origX = (pos.x - tx1) / origScale;
	var origY = (pos.y - ty1) / origScale;
	
	var tx = pos.x - newScale * origX;
	var ty = pos.y - newScale * origY;
	
	setTransform(image, tx, ty, newScale);
		
	event.preventDefault();
}

/**
 * Event position relative to untransformed image.
 */
function position(event, container, image) {
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
	
	return {x: x, y:y};
}

function setTransform(image, tx, ty, scale) {
	image.style["transform-origin"] = "0px 0px";
	image.style.transform = "translate(" + tx + "px, " + ty + "px) scale(" + scale + ")";
	
	image.scale = scale;
	image.tx = tx;
	image.ty = ty;
}

function withDefault(value, defaultValue) {
	return value == null ? defaultValue : value;
}

function onImageClick(event) {
	var image = imageElement();

	var transform = image.style.transform;
	
	if (transform == "none" || transform == "") {
		var container = containerElement();
		
		var width = parseInt(image.getAttribute("data-width"));
		var height = parseInt(image.getAttribute("data-height"));
		
		var containerWidth = container.offsetWidth;
		var containerHeight = container.offsetHeight;
		
		var middleX = containerWidth / 2;
		var middleY = containerHeight / 2;
	
		// Scale to display the image 1:1 on screen (it is fit to it's container using CSS scaling).
		var scale = 1 / Math.min(containerWidth / width, containerHeight / height);
		
		var pos = position(event, container, image);	
	
		var tx = -pos.x * scale + middleX - image.offsetLeft;
		var ty = -pos.y * scale + middleY - image.offsetTop;

		var maxTx = -image.offsetLeft;		
		var minTx = -image.offsetLeft - width + containerWidth;		
		if (minTx < maxTx) {
			if (tx > maxTx) {
				tx = maxTx;
			} else if (tx < minTx) {
				tx = minTx;
			}
		}

		var maxTy = -image.offsetTop;
		var minTy = -image.offsetTop - height + containerHeight;
		if (minTy < maxTy) {
			if (ty > maxTy) {
				ty = maxTy;
			} else if (ty < minTy) {
				ty = minTy;
			}
		}
		
		setTransform(image, tx, ty, scale);
	} else {
		image.style.transform = "none";
		delete image.tx;
		delete image.ty;
		delete image.scale;
	}
}

function onImagePanStart(event) {
	var container = containerElement();
	var image = imageElement();

	var startX = event.pageX;
	var startY = event.pageY;
	
	var tx = withDefault(image.tx, 0);
	var ty = withDefault(image.ty, 0);
	
	container.startX = startX - tx;
	container.startY = startY - ty;
	
	container.addEventListener("mouseup", onImagePanStop);
	container.addEventListener("mousemove", onImagePan);
}

function imageElement() {
	return document.getElementById("image");
}

function containerElement() {
	return document.getElementById("image-container");
}

function onImagePanStop(event) {
	var container = containerElement();
	
	container.removeEventListener("mouseup", onImagePanStop);
	container.removeEventListener("mousemove", onImagePan);
	
	if (container.moved) {
		delete container.moved;
	} else {
		onImageClick(event);
	}
}

function onImagePan(event) {
	var container = containerElement();
	
	var x = event.pageX;
	var y = event.pageY;
	
	var tx = x - container.startX;
	var ty = y - container.startY;
	
	var scale = withDefault(image.scale, 1);
	
	image.style.transform = "translate(" + tx + "px, " + ty + "px) scale(" + scale + ")";
	image.tx = tx;
	image.ty = ty;
	
	container.moved = true;
}

function onLoad(event) {
	document.addEventListener("keydown", onKeyDown);
	
	var container = containerElement();
	if (container != null) {
		container.addEventListener("wheel", onImageZoom);
		container.addEventListener("mousedown", onImagePanStart);
	}
}

window.addEventListener("load", onLoad);

