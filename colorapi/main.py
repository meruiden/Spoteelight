# This is a sample Python script.

# Press Shift+F10 to execute it or replace it with your code.
# Press Double Shift to search everywhere for classes, files, tool windows, actions, and settings.
from spotify_background_color import SpotifyBackgroundColor
from PIL import Image
import numpy as np
from flask import Flask
from flask import request
from flask import json

app = Flask(__name__)

@app.route('/color', methods=['POST'])
def get_image_color(request):
    print("color!")
    imagefile = request.files.get('image')
    colors = SpotifyBackgroundColor(img=np.array(Image.open(imagefile)))
    color = colors.best_color()

    return json.dumps({
            "r": round(color[0]),
            "g": round(color[1]),
            "b": round(color[2])
    }), 200


def init():
    app.run(debug=True, host='localhost', port=8000)

if __name__ == '__main__':
    init()
