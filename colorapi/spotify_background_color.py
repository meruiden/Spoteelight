import numpy as np
from sklearn.cluster import KMeans
from PIL import Image


class SpotifyBackgroundColor():
    def __init__(self, img, format='RGB', image_processing_size=None):
        if format == 'RGB':
            self.img = img
        elif format == 'BGR':
            self.img = self.img[..., ::-1]
        else:
            raise ValueError('Invalid format. Only RGB and BGR image '\
                             'format supported.')

        if image_processing_size:
            img = Image.fromarray(self.img)
            self.img = np.asarray(img.resize(image_processing_size, Image.BILINEAR))

    def best_color(self, k=8, color_tol=10):
        artwork = self.img.copy()
        self.img = self.img.reshape((self.img.shape[0]*self.img.shape[1], 3))

        clt = KMeans(n_clusters=k)
        clt.fit(self.img)
        hist = self.find_histogram(clt)
        centroids = clt.cluster_centers_

        colorfulness = [self.colorfulness(color[0], color[1], color[2]) for color in centroids]
        max_colorful = np.max(colorfulness)

        if max_colorful < color_tol:
            # If not colorful, set to gray
            best_color = [230, 230, 230]
        else:
            # Pick the most colorful color
            best_color = centroids[np.argmax(colorfulness)]

        return best_color[0], best_color[1], best_color[2]

    def find_histogram(self, clt):
        num_labels = np.arange(0, len(np.unique(clt.labels_)) + 1)
        hist, _ = np.histogram(clt.labels_, bins=num_labels)

        hist = hist.astype('float')
        hist /= hist.sum()

        return hist

    def colorfulness(self, r, g, b):
        rg = np.absolute(r - g)
        yb = np.absolute(0.5 * (r + g) - b)

        # Compute the mean and standard deviation of both `rg` and `yb`.
        rg_mean, rg_std = (np.mean(rg), np.std(rg))
        yb_mean, yb_std = (np.mean(yb), np.std(yb))

        # Combine the mean and standard deviations.
        std_root = np.sqrt((rg_std ** 2) + (yb_std ** 2))
        mean_root = np.sqrt((rg_mean ** 2) + (yb_mean ** 2))

        return std_root + (0.3 * mean_root)